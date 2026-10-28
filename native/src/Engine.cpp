#include "Engine.hpp"

#include "GCodePreview.hpp"
#include "Thumbnail.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <optional>
#include <set>

#include <boost/algorithm/string/trim.hpp>
#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>
#include <LocalesUtils.hpp>
#include <nlohmann/json.hpp>

#include <arrange-wrapper/ArrangeSettingsView.hpp>
#include <arrange-wrapper/ModelArrange.hpp>
#include <arrange-wrapper/Scene.hpp>
#include <arrange-wrapper/SceneBuilder.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/AABBMesh.hpp"
#include "libslic3r/BuildVolume.hpp"
#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/FileReader.hpp"
#include "libslic3r/Format/3mf.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelProcessing.hpp"
#include "libslic3r/MultipleBeds.hpp"
#include "libslic3r/ObjectID.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Slicing.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "libslic3r/Utils.hpp"

using nlohmann::json;
using namespace Slic3r;

namespace andslice {

// The plate for undo and redo. Copies of the model share the meshes, so they are cheap.
struct Engine::PlateState {
    Model                     model;
    std::map<uint64_t, float> layer_quality;
    WipeTowerShape            wipe_tower;
};

// How many changes undo goes back.
static constexpr size_t UndoLimit = 30;

// An object being painted: a TriangleSelector and a raycaster per model part, as in
// PrusaSlicer's painting gizmos, plus the undo history of the strokes.
struct Engine::PaintSession {
    uint64_t                                       object_id = 0;
    int                                            mode = PaintSupports;
    std::vector<ModelVolume*>                      volumes;
    std::vector<std::unique_ptr<TriangleSelector>> selectors;
    std::vector<std::unique_ptr<AABBMesh>>         raycasters;
    std::set<int>                                  states;     // states that may be painted somewhere
    bool                                           stroke = false;
    int                                            last_volume = -1;
    Vec3f                                          last_hit = Vec3f::Zero();
    int                                            last_facet = -1;
    std::vector<std::vector<TriangleSelector::TriangleSplittingData>> undo; // state before each stroke
    float                                          highlight_angle = 0.f;
    std::vector<float>                             highlight;  // overhang triangles, object coordinates
    std::shared_ptr<const PaintMesh>               mesh;
    // The plate before the session, for the plate's undo once something is painted.
    std::optional<Engine::PlateState>              before;
};

Engine& Engine::get()
{
    static Engine engine;
    return engine;
}

Engine::Engine() : m_model(std::make_unique<Model>()) {}
Engine::~Engine() = default;

void Engine::init(const std::string& data_dir, const std::string& resources_dir, const std::vector<std::string>& bundles)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    set_logging_level(2); // warnings and errors only
    set_data_dir(data_dir);
    set_resources_dir(resources_dir);
    boost::filesystem::create_directories(data_dir);
    m_data_dir = data_dir;

    // Start from an empty plate; init runs once per app process (or per test).
    // The paint session points into the model, so it goes first.
    m_paint.reset();
    m_paint_cache.clear();
    m_model->clear_objects();
    m_meshes.clear();
    m_layer_quality.clear();
    m_undo.clear();
    m_redo.clear();
    m_selected = -1;
    m_wipe_tower = {};
    touch_plate();
    {
        std::lock_guard<std::mutex> preview_lock(m_preview_mutex);
        m_preview.reset();
        ++m_preview_revision;
    }

    load_profiles_locked(bundles);
}

void Engine::load_profiles(const std::vector<std::string>& bundles)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    load_profiles_locked(bundles);
}

void Engine::load_profiles_locked(const std::vector<std::string>& bundles)
{
    if (bundles.empty())
        throw RuntimeError("No printer profiles to load.");
    // Like PresetBundle::load_system_presets(): the first vendor bundle is loaded directly,
    // the others into their own bundles that are then merged.
    auto bundle = std::make_unique<PresetBundle>();
    bundle->set_default_suppressed(true);
    for (size_t i = 0; i < bundles.size(); ++i) {
        if (i == 0) {
            bundle->load_configbundle(bundles[i], PresetBundle::LoadConfigBundleAttribute::LoadSystem,
                                      ForwardCompatibilitySubstitutionRule::EnableSystemSilent);
        } else {
            PresetBundle other;
            other.load_configbundle(bundles[i], PresetBundle::LoadConfigBundleAttribute::LoadSystem,
                                    ForwardCompatibilitySubstitutionRule::EnableSystemSilent);
            for (const std::string& name : bundle->merge_presets(std::move(other)))
                BOOST_LOG_TRIVIAL(warning) << "Preset " << name << " of " << bundles[i] << " exists already";
        }
    }
    bundle->update_system_maps();

    // User presets: saved settings and custom printers, one ini file per preset.
    PresetsConfigSubstitutions substitutions;
    for (auto [collection, subdir] : { std::pair<PresetCollection*, const char*>{ &bundle->prints, "print" },
                                       { &bundle->filaments, "filament" }, { &bundle->printers, "printer" } }) {
        boost::filesystem::create_directories(boost::filesystem::path(m_data_dir) / subdir);
        collection->load_presets(m_data_dir, subdir, substitutions, ForwardCompatibilitySubstitutionRule::EnableSilent);
    }
    m_bundle = std::move(bundle);

    // Loading a system bundle selects nothing. Select the first printer and a
    // compatible print and filament, like PresetBundle::load_selections() does
    // for the desktop app, so that every later compatibility update is valid.
    PresetBundle& b = *m_bundle;
    auto first_visible = [](const PresetCollection& c) {
        for (const Preset& p : c)
            if (p.is_visible && !p.is_default)
                return p.name;
        return c.first_visible().name;
    };
    // The default printer is the first variant of the first bundle's first model, as in PrusaSlicer's wizard.
    std::string printer = first_visible(b.printers);
    const auto first_vendor = b.vendors.find(boost::filesystem::path(bundles.front()).stem().string());
    if (first_vendor != b.vendors.end() && !first_vendor->second.models.empty()) {
        const VendorProfile::PrinterModel& model = first_vendor->second.models.front();
        for (const Preset& p : b.printers)
            if (!model.variants.empty() && p.config.opt_string("printer_model") == model.id &&
                p.config.opt_string("printer_variant") == model.variants.front().name) {
                printer = p.name;
                break;
            }
    }
    b.printers.select_preset_by_name(printer, true);
    b.prints.select_preset_by_name(first_visible(b.prints), true);
    b.filaments.select_preset_by_name(first_visible(b.filaments), true);
    b.extruders_filaments.clear();
    b.extruders_filaments.emplace_back(ExtruderFilaments(&b.filaments, 0, b.filaments.get_selected_preset_name()));
    b.update_multi_material_filament_presets();
    b.update_compatible(PresetSelectCompatibleType::Always);
}

// ------------------------------------------------------------------- profiles

static json preset_names(const PresetCollection& collection, bool compatible_only)
{
    json out = json::array();
    for (const Preset& p : collection) {
        if (p.is_default || !p.is_visible || (compatible_only && !p.is_compatible))
            continue;
        out.push_back(p.name);
    }
    return out;
}

std::string Engine::presets_json(const std::string& printer)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!printer.empty())
        m_bundle->printers.select_preset_by_name(printer, true);
    m_bundle->update_compatible(PresetSelectCompatibleType::Always);

    const Preset& selected = m_bundle->printers.get_edited_preset();
    json out;
    out["printer"]   = selected.name;
    out["printers"]  = preset_names(m_bundle->printers, false);
    out["prints"]    = preset_names(m_bundle->prints, true);
    // Since PrusaSlicer 2.7 filament compatibility is kept per extruder, not on the presets.
    json filaments = json::array();
    json templates = json::array();
    if (!m_bundle->extruders_filaments.empty())
        for (const Filament& f : m_bundle->extruders_filaments.front()) {
            const Preset& p = *f.preset;
            if (p.is_default || !p.is_visible || !f.is_compatible)
                continue;
            // Generic template filaments are listed apart from the printer's own.
            (p.vendor && p.vendor->templates_profile ? templates : filaments).push_back(p.name);
        }
    out["filaments"] = filaments;
    out["template_filaments"] = templates;
    out["default_print"]    = selected.config.opt_string("default_print_profile");
    const auto* def_fil = selected.config.option<ConfigOptionStrings>("default_filament_profile");
    out["default_filament"] = def_fil && !def_fil->values.empty() ? def_fil->values.front() : std::string();
    out["extruders"] = selected.config.option<ConfigOptionFloats>("nozzle_diameter")->values.size();

    json user;
    for (auto [collection, key] : { std::pair<const PresetCollection*, const char*>{ &m_bundle->prints, "print" },
                                    { &m_bundle->filaments, "filament" }, { &m_bundle->printers, "printer" } }) {
        json names = json::array();
        for (const Preset& p : *collection)
            if (p.is_user() && !p.is_external)
                names.push_back(p.name);
        user[key] = names;
    }
    out["user"] = user;

    // The printer's vendor model and its nozzle variants. A user printer reports those of its parent.
    const Preset* system = selected.is_system ? &selected : m_bundle->printers.get_preset_parent(selected);
    out["vendor"]  = system && system->vendor ? system->vendor->id : std::string();
    out["model"]   = selected.config.opt_string("printer_model");
    out["variant"] = selected.config.opt_string("printer_variant");
    json variants = json::array();
    if (system && system->vendor) {
        for (const VendorProfile::PrinterModel& model : system->vendor->models)
            if (model.id == system->config.opt_string("printer_model"))
                for (const VendorProfile::PrinterVariant& v : model.variants)
                    if (const Preset* p = m_bundle->printers.find_system_preset_by_model_and_variant(model.id, v.name))
                        variants.push_back({ {"variant", v.name}, {"preset", p->name} });
    }
    out["variants"] = variants;
    return out.dump();
}

std::string Engine::printer_for_model(const std::string& model, const std::string& variant)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Preset* p = m_bundle->printers.find_system_preset_by_model_and_variant(model, variant);
    return p ? p->name : std::string();
}

static PresetCollection& collection_of(PresetBundle& bundle, const std::string& type)
{
    if (type == "print")    return bundle.prints;
    if (type == "filament") return bundle.filaments;
    if (type == "printer")  return bundle.printers;
    throw RuntimeError("Unknown profile type: " + type);
}

std::string Engine::save_preset(const std::string& type, const std::string& name, const std::string& printer,
                                const std::string& print, const std::vector<std::string>& filaments,
                                const std::vector<std::pair<std::string, std::string>>& overrides)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::string trimmed = name;
    boost::algorithm::trim(trimmed);
    if (trimmed.empty())
        throw RuntimeError("The profile needs a name.");
    PresetCollection& collection = collection_of(*m_bundle, type);
    if (const Preset* existing = collection.find_preset(trimmed); existing && !existing->is_user())
        throw RuntimeError("A built-in profile is called \"" + trimmed + "\". Pick another name.");

    resolve_config(printer, print, filaments);
    DynamicPrintConfig& edited = collection.get_edited_preset().config;
    for (const auto& [key, value] : overrides) {
        if (!edited.has(key))
            continue; // the override belongs to another profile type
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        edited.set_deserialize(key, value, ctx);
        // A filament profile holds one value per option; multi-extruder overrides keep the first.
        if (type == "filament")
            if (auto* vec = dynamic_cast<ConfigOptionVectorBase*>(edited.option(key)); vec && vec->size() > 1)
                vec->resize(1);
    }
    // Saving inserts into the filament collection; ExtruderFilaments point into it.
    m_bundle->cache_extruder_filaments_names();
    collection.save_current_preset(trimmed);
    m_bundle->reset_extruder_filaments();
    m_bundle->update_compatible(PresetSelectCompatibleType::Never);
    return trimmed;
}

void Engine::delete_preset(const std::string& type, const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PresetCollection& collection = collection_of(*m_bundle, type);
    const Preset* preset = collection.find_preset(name);
    if (preset == nullptr || !preset->is_user())
        throw RuntimeError("Only your own profiles can be deleted.");
    // ExtruderFilaments point into the filament collection, which is about to change.
    m_bundle->cache_extruder_filaments_names();
    collection.delete_preset(name);
    m_bundle->reset_extruder_filaments();
    m_bundle->update_compatible(PresetSelectCompatibleType::Never);
}

DynamicPrintConfig Engine::resolve_config(const std::string& printer, const std::string& print,
                                          const std::vector<std::string>& filaments)
{
    PresetBundle& b = *m_bundle;
    if (!b.printers.select_preset_by_name(printer, true))
        throw RuntimeError("Unknown printer profile: " + printer);
    b.update_compatible(PresetSelectCompatibleType::Always);
    if (!b.prints.select_preset_by_name(print, true))
        throw RuntimeError("Unknown print profile: " + print);
    if (filaments.empty())
        throw RuntimeError("No filament profile selected.");
    for (const std::string& filament : filaments)
        if (b.filaments.find_preset(filament) == nullptr)
            throw RuntimeError("Unknown filament profile: " + filament);
    b.filaments.select_preset_by_name(filaments.front(), true);
    // One filament per extruder of the printer. This also sizes the purge volume matrix.
    b.update_multi_material_filament_presets();
    for (size_t i = 0; i < b.extruders_filaments.size(); ++i)
        b.extruders_filaments[i].select_filament(filaments[std::min(i, filaments.size() - 1)], true);
    return b.full_config();
}

std::string Engine::config_json(const std::string& printer, const std::string& print,
                                const std::vector<std::string>& filaments)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DynamicPrintConfig config = resolve_config(printer, print, filaments);
    json out = json::object();
    for (const std::string& key : config.keys())
        out[key] = config.opt_serialize(key);
    return out.dump();
}

static const char* option_type_name(ConfigOptionType type)
{
    switch (type) {
    case coFloat: case coFloats:                   return "float";
    case coInt: case coInts:                       return "int";
    case coString: case coStrings:                 return "string";
    case coPercent: case coPercents:               return "percent";
    case coFloatOrPercent: case coFloatsOrPercents: return "float_or_percent";
    case coPoint: case coPoints: case coPoint3:    return "point";
    case coBool: case coBools:                     return "bool";
    case coEnum: case coEnums:                     return "enum";
    default:                                       return "other";
    }
}

static json option_defs(const std::vector<std::string>& keys)
{
    json out = json::array();
    for (const std::string& key : keys) {
        const ConfigOptionDef* def = print_config_def.get(key);
        if (def == nullptr || def->label.empty() && def->full_label.empty())
            continue;
        json o;
        o["key"]        = key;
        o["label"]      = def->label;
        o["full_label"] = def->full_label.empty() ? def->label : def->full_label;
        o["category"]   = def->category;
        o["tooltip"]    = def->tooltip;
        o["sidetext"]   = def->sidetext;
        o["type"]       = option_type_name(def->type);
        o["vector"]     = !def->is_scalar();
        o["nullable"]   = def->nullable;
        o["multiline"]  = def->multiline;
        o["mode"]       = int(def->mode);
        o["ratio_over"] = def->ratio_over;
        if (def->min > -FLT_MAX) o["min"] = def->min;
        if (def->max < FLT_MAX)  o["max"] = def->max;
        if (def->enum_def && def->enum_def->has_values()) {
            json values = json::array();
            for (size_t i = 0; i < def->enum_def->values().size(); ++i)
                values.push_back({ {"value", def->enum_def->value(int(i))}, {"label", def->enum_def->label(int(i))} });
            o["enum"] = values;
            o["enum_open"] = def->is_gui_type_enum_open();
        }
        out.push_back(o);
    }
    return out;
}

std::string Engine::option_defs_json()
{
    json out;
    out["print"]    = option_defs(Preset::print_options());
    out["filament"] = option_defs(Preset::filament_options());
    out["printer"]  = option_defs(Preset::printer_options());
    return out.dump();
}

void Engine::set_printer(const std::string& printer)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_bundle->printers.select_preset_by_name(printer, true))
        throw RuntimeError("Unknown printer profile: " + printer);
    const DynamicPrintConfig& cfg = m_bundle->printers.get_edited_preset().config;
    m_bed.outline.clear();
    for (const Vec2d& p : cfg.option<ConfigOptionPoints>("bed_shape")->values)
        m_bed.outline.push_back({ float(p.x()), float(p.y()) });
    m_bed.max_height = float(cfg.opt_float("max_print_height"));
    s_multiple_beds.update_build_volume(BoundingBoxf(cfg.option<ConfigOptionPoints>("bed_shape")->values));
    touch_plate();
}

BedShape Engine::bed() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_bed;
}

// ------------------------------------------------------------------- plate

void Engine::touch_plate() { ++m_plate_revision; }

ModelObject* Engine::find_object(uint64_t id)
{
    for (ModelObject* o : m_model->objects)
        if (o->id().id == id)
            return o;
    return nullptr;
}

std::shared_ptr<const MeshData> Engine::mesh_for(const ModelObject& object)
{
    for (auto& [id, mesh] : m_meshes)
        if (id == object.id().id)
            return mesh;

    // Like ModelObject::raw_indexed_triangle_set(), but remembers where each model part starts,
    // so the renderer can draw every part in the color of its filament.
    auto mesh = std::make_shared<MeshData>();
    mesh->min = { FLT_MAX, FLT_MAX, FLT_MAX };
    mesh->max = { -FLT_MAX, -FLT_MAX, -FLT_MAX };
    for (const ModelVolume* volume : object.volumes) {
        if (!volume->is_model_part())
            continue;
        const indexed_triangle_set& its = volume->mesh().its;
        const Transform3d& m = volume->get_matrix();
        const uint32_t base  = uint32_t(mesh->positions.size() / 3);
        const uint32_t first = uint32_t(mesh->indices.size());
        for (const stl_vertex& p : its.vertices) {
            const Vec3f v = (m * p.cast<double>()).cast<float>();
            for (int i = 0; i < 3; ++i) {
                mesh->positions.push_back(v[i]);
                mesh->min[i] = std::min(mesh->min[i], v[i]);
                mesh->max[i] = std::max(mesh->max[i], v[i]);
            }
        }
        const bool flip = volume->is_left_handed();
        for (const stl_triangle_vertex_indices& f : its.indices) {
            mesh->indices.push_back(base + uint32_t(f[flip ? 1 : 0]));
            mesh->indices.push_back(base + uint32_t(f[flip ? 0 : 1]));
            mesh->indices.push_back(base + uint32_t(f[2]));
        }
        mesh->parts.push_back({ first, uint32_t(mesh->indices.size()) - first });
    }
    m_meshes.emplace_back(object.id().id, mesh);
    return mesh;
}

static BuildVolume build_volume(const BedShape& bed)
{
    std::vector<Vec2d> pts;
    for (const auto& p : bed.outline)
        pts.emplace_back(p[0], p[1]);
    return BuildVolume(pts, bed.max_height);
}

static Points bed_points(const BedShape& bed)
{
    Points pts;
    for (const auto& p : bed.outline)
        pts.emplace_back(scaled(Vec2d(p[0], p[1])));
    return pts;
}

void Engine::place_new_objects(size_t first_new)
{
    BoundingBoxf bb;
    for (const auto& p : m_bed.outline)
        bb.merge(Vec2d(p[0], p[1]));
    const Vec2d center = bb.center();

    // ModelObject::translate() keeps the cached min/max Z, so invalidate it
    // before ensure_on_bed() looks at the new geometry.
    for (size_t i = first_new; i < m_model->objects.size(); ++i) {
        ModelObject* o = m_model->objects[i];
        o->center_around_origin();
        o->invalidate_bounding_box();
    }
    if (first_new == 0 && m_model->objects.size() == 1) {
        // A single object on an empty plate goes to the bed center.
        ModelObject* o = m_model->objects.front();
        for (ModelInstance* inst : o->instances)
            inst->set_offset(Vec3d(center.x(), center.y(), inst->get_offset().z()));
        o->invalidate_bounding_box();
        o->ensure_on_bed();
    } else {
        for (size_t i = first_new; i < m_model->objects.size(); ++i)
            m_model->objects[i]->ensure_on_bed();
        arrange_plate();
    }
    m_model->update_print_volume_state(build_volume(m_bed));
}

namespace {

// The wipe tower as an item for PrusaSlicer's arrangement, following the
// desktop app's ArrangeableWT / WTH (slic3r/GUI/Jobs/ArrangeJob2.cpp).
class ArrangeableWipeTower : public arr2::ArrangeableWipeTowerBase
{
public:
    ArrangeableWipeTower(const WipeTowerShape& shape, Vec2d& position, std::function<bool(int)> selected)
        : arr2::ArrangeableWipeTowerBase{ wipe_tower_instance_id(0), outline(shape), 0, std::move(selected) }
        , m_position{ position }, m_start{ position }
    {}

    void transform(const Vec2d& translation, double /*rotation*/) override { m_position = m_start + translation; }

private:
    static Polygon outline(const WipeTowerShape& s)
    {
        const double b = s.brim;
        Polygon poly({ scaled(Vec2d(-b, -b)), scaled(Vec2d(s.width + b, -b)),
                       scaled(Vec2d(s.width + b, s.depth + b)), scaled(Vec2d(-b, s.depth + b)) });
        poly.translate(scaled(Vec2d(s.x, s.y)));
        return poly;
    }

    Vec2d& m_position;
    Vec2d  m_start;
};

class WipeTowerHandler : public arr2::WipeTowerHandler
{
public:
    WipeTowerHandler(const WipeTowerShape& shape, Vec2d& position) : m_shape{ shape }, m_position{ position } {}

    void visit(std::function<void(arr2::Arrangeable&)> fn) override
    {
        ArrangeableWipeTower wt{ m_shape, m_position, m_selected };
        fn(wt);
    }
    void visit(std::function<void(const arr2::Arrangeable&)> fn) const override
    {
        ArrangeableWipeTower wt{ m_shape, m_position, m_selected };
        fn(wt);
    }
    void     set_selection_predicate(std::function<bool(int)> pred) override { m_selected = std::move(pred); }
    ObjectID get_id() const override { return wipe_tower_instance_id(0); }

private:
    WipeTowerShape           m_shape;
    Vec2d&                   m_position;
    std::function<bool(int)> m_selected = [](int) { return false; };
};

} // namespace

void Engine::arrange_plate()
{
    arr2::ArrangeSettings settings;
    settings.set_distance_from_objects(m_arrange_distance);
    const arr2::ArrangeBed bed = arr2::to_arrange_bed(bed_points(m_bed), Vec2crd{0, 0});
    if (!m_wipe_tower.visible) {
        arrange_objects(*m_model, bed, settings);
        return;
    }
    // Arrange the wipe tower together with the objects, as PrusaSlicer does.
    Vec2d position(m_wipe_tower.x, m_wipe_tower.y);
    std::vector<AnyPtr<arr2::WipeTowerHandler>> handlers;
    handlers.emplace_back(std::make_unique<WipeTowerHandler>(m_wipe_tower, position));
    arr2::arrange(arr2::SceneBuilder{}
                      .set_bed(bed)
                      .set_arrange_settings(settings)
                      .set_wipe_tower_handlers(std::move(handlers))
                      .set_model(*m_model));
    m_wipe_tower.x = float(position.x());
    m_wipe_tower.y = float(position.y());
    m_model->wipe_tower().position = position;
}

void Engine::set_arrange_distance(float mm)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_arrange_distance = std::max(mm, 0.f);
}

std::string Engine::add_model(const std::string& path, const std::string& display_name)
{
    // Parsing can take a while for big files, so do it without holding the lock.
    Model loaded = FileReader::load_model(path, FileReader::LoadAttribute::AddDefaultInstances);
    if (loaded.objects.empty())
        throw RuntimeError("The file contains no printable objects.");

    std::lock_guard<std::mutex> lock(m_mutex);
    const size_t first_new = m_model->objects.size();
    json ids = json::array();
    for (ModelObject* src : loaded.objects) {
        ModelObject* o = m_model->add_object(*src);
        if (loaded.objects.size() == 1 && !display_name.empty())
            o->name = display_name;
        // Keep a single instance per object; duplicates are separate objects.
        while (o->instances.size() > 1)
            o->delete_instance(o->instances.size() - 1);
        if (o->instances.empty())
            o->add_instance();
        ids.push_back(o->id().id);
    }
    place_new_objects(first_new);
    touch_plate();
    return json{ {"ids", ids} }.dump();
}

void Engine::remove_object(uint64_t id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (ModelObject* o = find_object(id)) {
        end_paint_of(id);
        m_model->delete_object(o);
        m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(),
                                      [id](const auto& m) { return m.first == id; }),
                       m_meshes.end());
        m_layer_quality.erase(id);
        if (m_model->objects.empty())
            m_model->custom_gcode_per_print_z().gcodes.clear();
        if (m_selected == int64_t(id))
            m_selected = -1;
        touch_plate();
    }
}

void Engine::clear_plate()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_paint.reset();
    m_paint_cache.clear();
    m_model->clear_objects();
    // G-code at heights belongs to what was on the plate, such as a calibration tower.
    m_model->custom_gcode_per_print_z().gcodes.clear();
    m_meshes.clear();
    m_layer_quality.clear();
    m_selected = -1;
    touch_plate();
}

uint64_t Engine::duplicate_object(uint64_t id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr)
        return 0;
    const size_t first_new = m_model->objects.size();
    ModelObject* copy = m_model->add_object(*o);
    const uint64_t new_id = copy->id().id;
    if (const auto quality = m_layer_quality.find(id); quality != m_layer_quality.end())
        m_layer_quality[new_id] = quality->second;
    place_new_objects(first_new);
    touch_plate();
    return new_id;
}

static constexpr double deg2rad(double d) { return d * M_PI / 180.0; }
static constexpr double rad2deg(double r) { return r * 180.0 / M_PI; }

void Engine::set_transform(uint64_t id, const double t[8])
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty())
        return;
    ModelInstance* inst = o->instances.front();
    inst->set_rotation(Vec3d(deg2rad(t[2]), deg2rad(t[3]), deg2rad(t[4])));
    inst->set_scaling_factor(Vec3d(std::max(t[5], 1e-3), std::max(t[6], 1e-3), std::max(t[7], 1e-3)));
    inst->set_offset(Vec3d(t[0], t[1], inst->get_offset().z()));
    o->invalidate_bounding_box();
    o->ensure_on_bed();
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
}

// Möller–Trumbore ray/triangle intersection; returns the ray parameter or -1.
static double ray_triangle(const Vec3d& o, const Vec3d& d, const Vec3d& a, const Vec3d& b, const Vec3d& c)
{
    const Vec3d e1 = b - a, e2 = c - a;
    const Vec3d p = d.cross(e2);
    const double det = e1.dot(p);
    if (std::abs(det) < 1e-12)
        return -1.;
    const double inv = 1. / det;
    const Vec3d s = o - a;
    const double u = s.dot(p) * inv;
    if (u < 0. || u > 1.)
        return -1.;
    const Vec3d q = s.cross(e1);
    const double v = d.dot(q) * inv;
    if (v < 0. || u + v > 1.)
        return -1.;
    const double t = e2.dot(q) * inv;
    return t > 0. ? t : -1.;
}

struct RayHit {
    ModelObject* object = nullptr;
    double       t = std::numeric_limits<double>::max();
    Vec3d        normal = Vec3d::Zero(); // world space
};

static RayHit cast_ray(const Model& model, const std::vector<std::pair<uint64_t, std::shared_ptr<const MeshData>>>& meshes,
                       const Vec3d& origin, const Vec3d& dir, uint64_t only_id = 0)
{
    RayHit best;
    for (ModelObject* o : model.objects) {
        if (only_id != 0 && o->id().id != only_id)
            continue;
        const MeshData* mesh = nullptr;
        for (const auto& [id, m] : meshes)
            if (id == o->id().id)
                mesh = m.get();
        if (mesh == nullptr || o->instances.empty())
            continue;
        const Transform3d trafo = o->instances.front()->get_matrix();
        const Transform3d inv = trafo.inverse();
        const Vec3d lo = inv * origin;
        const Vec3d ld = (inv.linear() * dir).normalized();
        const auto& P = mesh->positions;
        for (size_t i = 0; i + 2 < mesh->indices.size(); i += 3) {
            const uint32_t ia = mesh->indices[i], ib = mesh->indices[i + 1], ic = mesh->indices[i + 2];
            const Vec3d a(P[3 * ia], P[3 * ia + 1], P[3 * ia + 2]);
            const Vec3d b(P[3 * ib], P[3 * ib + 1], P[3 * ib + 2]);
            const Vec3d c(P[3 * ic], P[3 * ic + 1], P[3 * ic + 2]);
            const double tl = ray_triangle(lo, ld, a, b, c);
            if (tl < 0.)
                continue;
            const double tw = (trafo * (lo + ld * tl) - origin).norm();
            if (tw < best.t) {
                best.t      = tw;
                best.object = o;
                best.normal = (trafo.linear().inverse().transpose() * (b - a).cross(c - a)).normalized();
            }
        }
    }
    return best;
}

int64_t Engine::pick(const float origin[3], const float dir[3])
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (ModelObject* o : m_model->objects)
        mesh_for(*o);
    const RayHit hit = cast_ray(*m_model, m_meshes, Vec3d(origin[0], origin[1], origin[2]),
                                Vec3d(dir[0], dir[1], dir[2]).normalized());
    return hit.object ? int64_t(hit.object->id().id) : -1;
}

bool Engine::lay_on_face(uint64_t id, const float origin[3], const float dir[3])
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty())
        return false;
    mesh_for(*o);
    const RayHit hit = cast_ray(*m_model, m_meshes, Vec3d(origin[0], origin[1], origin[2]),
                                Vec3d(dir[0], dir[1], dir[2]).normalized(), id);
    if (hit.object == nullptr)
        return false;

    // Rotate so the hit face's outward normal points straight down.
    ModelInstance* inst = o->instances.front();
    const Transform3d m = inst->get_matrix();
    const Eigen::Quaterniond q = Eigen::Quaterniond::FromTwoVectors(hit.normal, -Vec3d::UnitZ());
    Transform3d rotated = Transform3d::Identity();
    rotated.linear() = q.toRotationMatrix() * m.linear();
    rotated.translation() = m.translation();
    inst->set_transformation(Geometry::Transformation(rotated));
    o->invalidate_bounding_box();
    o->ensure_on_bed();
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
    return true;
}

void Engine::arrange()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_model->objects.empty())
        return;
    arrange_plate();
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
}

void Engine::set_extruder(uint64_t id, int part, int extruder)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || extruder < 1)
        return;
    if (part < 0) {
        o->config.set("extruder", extruder);
        for (ModelVolume* v : o->volumes)
            if (v->is_model_part())
                v->config.erase("extruder");
    } else {
        int index = 0;
        for (ModelVolume* v : o->volumes)
            if (v->is_model_part() && index++ == part)
                v->config.set("extruder", extruder);
    }
    touch_plate();
}

void Engine::mirror_object(uint64_t id, int axis)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty() || axis < 0 || axis > 2)
        return;
    end_paint_of(id);
    // A mirror along the world axis, put into the parts' own transformations: the instance keeps
    // its rotation and scale, which the transform tools edit as plain numbers.
    const Transform3d linear = o->instances.front()->get_matrix_no_offset();
    Transform3d mirror = Transform3d::Identity();
    mirror(axis, axis) = -1.;
    const Transform3d in_object = linear.inverse() * mirror * linear;
    for (ModelVolume* v : o->volumes)
        v->set_transformation(Geometry::Transformation(in_object * v->get_matrix()));
    m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(), [id](const auto& m) { return m.first == id; }), m_meshes.end());
    o->invalidate_bounding_box();
    o->ensure_on_bed();
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
}

std::string Engine::split_object(uint64_t id, bool into_parts)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    json ids = json::array();
    if (o == nullptr || o->instances.empty())
        return json{ {"ids", ids} }.dump();
    end_paint_of(id);
    if (into_parts) {
        const unsigned extruders = unsigned(m_bundle->printers.get_edited_preset().config.option<ConfigOptionFloats>("nozzle_diameter")->values.size());
        size_t parts = 0;
        for (size_t i = 0; i < o->volumes.size(); ++i)
            if (o->volumes[i]->is_model_part()) {
                const size_t n = ModelProcessing::split(o->volumes[i], extruders);
                parts += n;
                i += n - 1; // the new parts follow the split one
            }
        if (parts > 1) {
            // Parts choose their own filaments now.
            o->config.erase("extruder");
            m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(), [id](const auto& m) { return m.first == id; }), m_meshes.end());
            ids.push_back(id);
            touch_plate();
        }
        return json{ {"ids", ids} }.dump();
    }
    ModelObjectPtrs pieces;
    ModelProcessing::split(o, &pieces);
    if (pieces.size() <= 1) {
        // One piece: nothing to split. split() added it to the model already.
        for (ModelObject* piece : pieces)
            m_model->delete_object(piece);
        return json{ {"ids", ids} }.dump();
    }
    for (ModelObject* piece : pieces) {
        // As separate objects they stand on the bed where they were.
        piece->invalidate_bounding_box();
        piece->ensure_on_bed();
        ids.push_back(piece->id().id);
    }
    m_layer_quality.erase(id);
    m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(), [id](const auto& m) { return m.first == id; }), m_meshes.end());
    m_model->delete_object(o);
    m_selected = -1;
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
    return json{ {"ids", ids} }.dump();
}

std::string Engine::cut_object(uint64_t id, double height, bool keep_upper, bool keep_lower, bool upper_on_cut)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    json ids = json::array();
    if (o == nullptr || o->instances.empty() || (!keep_upper && !keep_lower))
        return json{ {"ids", ids} }.dump();
    const BoundingBoxf3 bb = o->instance_bounding_box(0);
    if (height <= bb.min.z() + EPSILON || height >= bb.max.z() - EPSILON)
        throw RuntimeError("Cut somewhere between the bottom and the top of the object.");
    end_paint_of(id);
    m_cut_plane.visible = false;

    // A horizontal plane through the object's center at the height, relative to the instance, as the cut tool builds it.
    const Vec3d offset = o->instances.front()->get_offset();
    const Transform3d cut_matrix = Geometry::translation_transform(Vec3d(0., 0., height - offset.z()));
    // (enum_bitmask's |= does not compile, so the bits are combined with |.)
    ModelObjectCutAttributes attributes = ModelObjectCutAttribute::InvalidateCutInfo;
    if (keep_upper)   attributes = attributes | ModelObjectCutAttribute::KeepUpper;
    if (keep_lower)   attributes = attributes | ModelObjectCutAttribute::KeepLower;
    if (upper_on_cut) attributes = attributes | ModelObjectCutAttribute::PlaceOnCutUpper;
    Cut cut(o, 0, cut_matrix, attributes);
    const ModelObjectPtrs& parts = cut.perform_with_plane();
    if (parts.empty())
        return json{ {"ids", ids} }.dump();

    m_layer_quality.erase(id);
    m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(), [id](const auto& m) { return m.first == id; }), m_meshes.end());
    m_model->delete_object(o);
    m_selected = -1;
    const size_t first_new = m_model->objects.size();
    for (const ModelObject* part : parts) {
        ModelObject* added = m_model->add_object(*part);
        while (added->instances.size() > 1)
            added->delete_instance(added->instances.size() - 1);
        ids.push_back(added->id().id);
    }
    // The parts would stand in each other; arrange makes room.
    place_new_objects(first_new);
    touch_plate();
    return json{ {"ids", ids} }.dump();
}

void Engine::set_cut_plane(uint64_t id, double height)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = height >= 0. ? find_object(id) : nullptr;
    if (o == nullptr || o->instances.empty()) {
        m_cut_plane.visible = false;
    } else {
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        const float margin = 5.f;
        m_cut_plane = { true, float(height), float(bb.min.x()) - margin, float(bb.min.y()) - margin,
                        float(bb.max.x()) + margin, float(bb.max.y()) + margin };
    }
    touch_plate();
}

CutPlane Engine::cut_plane() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_cut_plane;
}

void Engine::set_wipe_tower(const WipeTowerShape& shape)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_wipe_tower = shape;
    m_model->wipe_tower().position = Vec2d(shape.x, shape.y);
    m_model->wipe_tower().rotation = 0.;
    touch_plate();
}

WipeTowerShape Engine::wipe_tower() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_wipe_tower;
}

void Engine::center_object(uint64_t id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty())
        return;
    BoundingBoxf bed_bb;
    for (const auto& p : m_bed.outline)
        bed_bb.merge(Vec2d(p[0], p[1]));
    const BoundingBoxf3 bb = o->instance_bounding_box(0);
    ModelInstance* inst = o->instances.front();
    const Vec3d shift(bed_bb.center().x() - bb.center().x(), bed_bb.center().y() - bb.center().y(), 0.);
    inst->set_offset(inst->get_offset() + shift);
    o->invalidate_bounding_box();
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
}

std::string Engine::objects_json()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json out = json::array();
    for (ModelObject* o : m_model->objects) {
        if (o->instances.empty())
            continue;
        const ModelInstance* inst = o->instances.front();
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        const Vec3d off = inst->get_offset(), rot = inst->get_rotation(), scale = inst->get_scaling_factor();
        json parts = json::array();
        bool painted = false, support_painted = false, seam_painted = false, fuzzy_painted = false;
        for (const ModelVolume* v : o->volumes) {
            painted = painted || v->is_mm_painted();
            support_painted = support_painted || (v->is_model_part() && !v->supported_facets.empty());
            seam_painted = seam_painted || (v->is_model_part() && !v->seam_facets.empty());
            fuzzy_painted = fuzzy_painted || v->is_fuzzy_skin_painted();
            if (v->is_model_part())
                parts.push_back({ {"name", v->name}, {"extruder", std::max(v->extruder_id(), 1)} });
        }
        const int extruder = o->config.has("extruder") ? std::max(o->config.extruder(), 1) : 1;
        const auto quality = m_layer_quality.find(o->id().id);
        // Own settings besides the filament slot, which the plate shows anyway.
        const t_config_option_keys keys = o->config.keys();
        const bool own_settings = !o->layer_config_ranges.empty() ||
            std::any_of(keys.begin(), keys.end(), [](const std::string& k) { return k != "extruder"; });
        out.push_back({
            {"id", o->id().id},
            {"name", o->name},
            {"offset", {off.x(), off.y(), off.z()}},
            {"rotation", {rad2deg(rot.x()), rad2deg(rot.y()), rad2deg(rot.z())}},
            {"scale", {scale.x(), scale.y(), scale.z()}},
            {"size", {bb.size().x(), bb.size().y(), bb.size().z()}},
            {"triangles", o->facets_count()},
            {"outside", inst->print_volume_state != ModelInstancePVS_Inside},
            {"extruder", extruder},
            {"parts", parts},
            {"painted", painted},
            {"support_painted", support_painted},
            {"seam_painted", seam_painted},
            {"fuzzy_painted", fuzzy_painted},
            {"layer_quality", quality == m_layer_quality.end() ? -1.f : quality->second},
            // Layer heights of its own, e.g. from a project, also without a quality.
            {"layer_profile", !o->layer_height_profile.empty()},
            {"own_settings", own_settings},
        });
    }
    return out.dump();
}

void Engine::select(int64_t id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_selected = id;
    touch_plate();
}

std::vector<PlateObject> Engine::plate_snapshot()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<PlateObject> out;
    for (ModelObject* o : m_model->objects) {
        if (o->instances.empty())
            continue;
        PlateObject po;
        po.id       = o->id().id;
        po.mesh     = mesh_for(*o);
        po.selected = int64_t(po.id) == m_selected;
        po.outside  = o->instances.front()->print_volume_state != ModelInstancePVS_Inside;
        for (const ModelVolume* v : o->volumes)
            if (v->is_model_part())
                po.part_extruders.push_back(std::max(v->extruder_id(), 1));
        po.paint = m_paint && m_paint->object_id == po.id ? m_paint->mesh : color_paint_for(*o);
        const Transform3f m = o->instances.front()->get_matrix().cast<float>();
        std::copy(m.data(), m.data() + 16, po.transform.begin());
        out.push_back(std::move(po));
    }
    return out;
}

// ------------------------------------------------------------------- calibration

namespace {

// A 45° overhang: a wall `height` high at x = 0 that leans out to x = height at the top, `depth` deep.
indexed_triangle_set its_make_overhang(float height, float depth)
{
    const float h = height, d = depth;
    return {
        { {0, 2, 1}, {3, 4, 5}, {0, 1, 4}, {0, 4, 3}, {1, 2, 5}, {1, 5, 4}, {0, 5, 2}, {0, 3, 5} },
        { {0, 0, 0}, {0, 0, h}, {h, 0, h}, {0, d, 0}, {0, d, h}, {h, d, h} },
    };
}

indexed_triangle_set its_moved(indexed_triangle_set its, float x, float y, float z)
{
    for (stl_vertex& v : its.vertices)
        v += stl_vertex(x, y, z);
    return its;
}

} // namespace

std::string Engine::add_calibration(const std::string& shape, int sections, double section_height,
                                    const std::vector<std::pair<double, std::string>>& commands)
{
    sections = std::clamp(sections, 1, 40);
    const float h = float(std::clamp(section_height, 1., 50.));
    std::vector<indexed_triangle_set> parts;
    std::string name;
    if (shape == "temperature_tower") {
        name = "Temperature tower";
        for (int s = 0; s < sections; ++s) {
            const float z = float(s) * h;
            parts.push_back(its_moved(its_make_cube(20., 12., h - 1.), 0.f, 0.f, z));
            // A narrower 1 mm band between the sections, to tell them apart.
            parts.push_back(its_moved(its_make_cube(16., 8., 1.), 2.f, 2.f, z + h - 1.f));
            parts.push_back(its_moved(its_make_overhang(h - 1.f, 12.f), 20.f, 0.f, z));
        }
    } else if (shape == "square_tower") {
        name = "Pressure advance tower";
        parts.push_back(its_make_cube(30., 30., double(h) * sections));
    } else if (shape == "hollow_cube") {
        name = "Flow cube";
        parts.push_back(its_make_cube(20., 20., h));
    } else {
        throw RuntimeError("Unknown calibration shape: " + shape);
    }

    std::lock_guard<std::mutex> lock(m_mutex);
    m_paint.reset();
    m_paint_cache.clear();
    m_model->clear_objects();
    m_meshes.clear();
    m_layer_quality.clear();
    m_selected = -1;
    ModelObject* o = m_model->add_object();
    o->name = name;
    for (indexed_triangle_set& its : parts) {
        ModelVolume* v = o->add_volume(TriangleMesh(std::move(its)));
        v->name = name;
    }
    o->add_instance();
    place_new_objects(0);

    // The commands run at the first layer at or above each height.
    CustomGCode::Info& info = m_model->custom_gcode_per_print_z();
    const size_t extruders = m_bundle->printers.get_edited_preset().config.option<ConfigOptionFloats>("nozzle_diameter")->values.size();
    info.mode = extruders > 1 ? CustomGCode::MultiAsSingle : CustomGCode::SingleExtruder;
    info.gcodes.clear();
    for (const auto& [z, gcode] : commands)
        info.gcodes.push_back({ z, CustomGCode::Custom, 1, std::string(), gcode });
    std::sort(info.gcodes.begin(), info.gcodes.end());
    touch_plate();
    return json{ {"ids", json::array({ o->id().id })} }.dump();
}

// ------------------------------------------------------------------- undo

Engine::PlateState Engine::current_state() const
{
    return { *m_model, m_layer_quality, m_wipe_tower };
}

void Engine::restore_state(PlateState&& state)
{
    // The meshes and paint drawn for an id may differ in the other state, e.g. after mirroring.
    m_paint.reset();
    m_paint_cache.clear();
    m_meshes.clear();
    *m_model        = std::move(state.model);
    m_layer_quality = std::move(state.layer_quality);
    m_wipe_tower    = state.wipe_tower;
    m_cut_plane.visible = false;
    if (m_selected >= 0 && find_object(uint64_t(m_selected)) == nullptr)
        m_selected = -1;
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
}

void Engine::push_undo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_undo.push_back(current_state());
    if (m_undo.size() > UndoLimit)
        m_undo.erase(m_undo.begin());
    m_redo.clear();
}

bool Engine::undo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_undo.empty() || m_paint)
        return false;
    m_redo.push_back(current_state());
    restore_state(std::move(m_undo.back()));
    m_undo.pop_back();
    return true;
}

bool Engine::redo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_redo.empty() || m_paint)
        return false;
    m_undo.push_back(current_state());
    restore_state(std::move(m_redo.back()));
    m_redo.pop_back();
    return true;
}

std::string Engine::undo_state_json()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return json{ {"can_undo", !m_undo.empty()}, {"can_redo", !m_redo.empty()} }.dump();
}

// ------------------------------------------------------------------- per-object settings

namespace {

// Options an object may override, as in PrusaSlicer's object list: object and region options.
const std::vector<std::string>& object_option_keys()
{
    static const std::vector<std::string> keys = [] {
        std::vector<std::string> out = PrintObjectConfig().keys();
        for (const std::string& key : PrintRegionConfig().keys())
            out.push_back(key);
        return out;
    }();
    return keys;
}

// Options a height range may override: region options and the layer height.
const std::vector<std::string>& range_option_keys()
{
    static const std::vector<std::string> keys = [] {
        std::vector<std::string> out = PrintRegionConfig().keys();
        out.push_back("layer_height");
        return out;
    }();
    return keys;
}

json config_values(const ModelConfig& config)
{
    json out = json::object();
    for (const std::string& key : config.keys())
        out[key] = config.option(key)->serialize();
    return out;
}

t_layer_config_ranges::iterator find_range(ModelObject& object, double min_z)
{
    return std::find_if(object.layer_config_ranges.begin(), object.layer_config_ranges.end(),
                        [min_z](const auto& range) { return std::abs(range.first.first - min_z) < 1e-4; });
}

} // namespace

// PrusaSlicer's adaptive layer heights for the object's height as it stands now, smoothed as its
// layer editing does by default.
static void set_adaptive_profile(ModelObject& object, const DynamicPrintConfig& config, float quality)
{
    object.invalidate_bounding_box();
    const SlicingParameters params = PrintObject::slicing_parameters(config, object, float(object.max_z()), Vec3d::Ones());
    const std::vector<double> profile = layer_height_profile_adaptive(params, object, quality);
    object.layer_height_profile.set(smooth_height_profile(profile, params, HeightProfileSmoothingParams(5, false)));
}

void Engine::set_layer_quality(uint64_t id, float quality)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty())
        return;
    if (quality < 0.f) {
        m_layer_quality.erase(id);
        o->layer_height_profile.clear();
    } else {
        m_layer_quality[id] = std::clamp(quality, 0.f, 1.f);
        // Worked out now with the selected profiles too, so a project saved before slicing has it.
        set_adaptive_profile(*o, m_bundle->full_config(), m_layer_quality[id]);
    }
    touch_plate();
}

void Engine::update_layer_profiles(const DynamicPrintConfig& config)
{
    for (ModelObject* o : m_model->objects)
        if (const auto it = m_layer_quality.find(o->id().id); it != m_layer_quality.end() && !o->instances.empty())
            set_adaptive_profile(*o, config, it->second);
}

std::string Engine::object_settings_json(uint64_t id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr)
        return "{}";
    json ranges = json::array();
    for (const auto& [range, config] : o->layer_config_ranges)
        ranges.push_back({ {"min", range.first}, {"max", range.second}, {"options", config_values(config)} });
    const auto quality = m_layer_quality.find(id);
    json options = config_values(o->config);
    // The filament slot is chosen on the plate, not here.
    options.erase("extruder");
    return json{
        {"options", options},
        {"ranges", ranges},
        {"layer_quality", quality == m_layer_quality.end() ? -1.f : quality->second},
        {"object_keys", object_option_keys()},
        {"range_keys", range_option_keys()},
    }.dump();
}

void Engine::set_object_option(uint64_t id, double range_min, const std::string& key, const std::string& value)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr)
        return;
    ModelConfig* config = &o->config;
    const std::vector<std::string>* allowed = &object_option_keys();
    if (range_min >= 0.) {
        const auto range = find_range(*o, range_min);
        if (range == o->layer_config_ranges.end())
            throw RuntimeError("No height range starts at that height.");
        config  = &range->second;
        allowed = &range_option_keys();
        // PrusaSlicer reads every range's layer height.
        if (key == "layer_height" && value.empty())
            return;
    }
    if (std::find(allowed->begin(), allowed->end(), key) == allowed->end())
        throw RuntimeError("\"" + key + "\" cannot be set for one object.");
    if (value.empty()) {
        config->erase(key);
    } else {
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        config->set_deserialize(key, value, ctx);
    }
    touch_plate();
}

void Engine::edit_height_range(uint64_t id, double old_min, double min_z, double max_z)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject* o = find_object(id);
    if (o == nullptr)
        return;
    ModelConfig config;
    if (old_min >= 0.) {
        const auto range = find_range(*o, old_min);
        if (range == o->layer_config_ranges.end())
            return;
        config.assign_config(range->second);
        o->layer_config_ranges.erase(range);
    } else {
        // The layer height the object prints with now, which PrusaSlicer requires of every range.
        const DynamicPrintConfig& print = m_bundle->prints.get_edited_preset().config;
        config.set("layer_height", o->config.has("layer_height") ? o->config.get().opt_float("layer_height") : print.opt_float("layer_height"));
    }
    if (min_z < max_z) {
        min_z = std::max(min_z, 0.);
        // Ranges may not overlap: the new one gives way to those already there.
        for (const auto& [range, _] : o->layer_config_ranges)
            if (min_z < range.second && range.first < max_z)
                throw RuntimeError("Height ranges cannot overlap.");
        o->layer_config_ranges[{ min_z, max_z }].assign_config(std::move(config));
    }
    touch_plate();
}

// ------------------------------------------------------------------- projects

void Engine::save_project(const std::string& path, const SliceRequest& settings)
{
    std::vector<PlateObject> scene = plate_snapshot();
    DynamicPrintConfig config;
    Model model;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        resolve_config(settings.printer, settings.print, settings.filaments);
        // Without the print host keys, as PrusaSlicer saves projects.
        config = m_bundle->full_config_secure();
        // The model is copied so writing it does not hold up the UI; the meshes are shared.
        model = *m_model;
    }
    for (const auto& [key, value] : settings.overrides) {
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        config.set_deserialize(key, value, ctx);
    }
    for (PlateObject& o : scene)
        o.selected = false;
    ThumbnailsParams params{ { Vec2d(256, 256) }, true, true, false, true };
    const ThumbnailsList thumbnails = render_thumbnails(scene, bed(), params);

    // Written next to the target first, so a failed save keeps the previous file.
    const std::string temp = path + ".part";
    if (!store_3mf(temp.c_str(), &model, &config, false, thumbnails.empty() ? nullptr : &thumbnails.front())) {
        boost::filesystem::remove(temp);
        throw RuntimeError("The project could not be saved.");
    }
    boost::filesystem::rename(temp, path);
}

std::string Engine::open_project(const std::string& path)
{
    // Parsing can take a while for big projects, so do it without holding the lock.
    DynamicPrintConfig         config;
    ConfigSubstitutionContext  substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    boost::optional<Semver>    version;
    Model loaded = FileReader::is_project_file(path)
        ? FileReader::load_model_with_config(path, &config, &substitutions, version, FileReader::LoadAttribute::AddDefaultInstances)
        : FileReader::load_model(path, FileReader::LoadAttribute::AddDefaultInstances);
    if (loaded.objects.empty())
        throw RuntimeError("The project contains no printable objects.");

    std::lock_guard<std::mutex> lock(m_mutex);
    m_paint.reset();
    m_paint_cache.clear();
    m_model->clear_objects();
    m_meshes.clear();
    m_layer_quality.clear();
    m_selected = -1;
    json ids = json::array();
    for (ModelObject* src : loaded.objects) {
        // One instance per object, as everywhere on the plate: further instances become copies.
        for (size_t i = 0; i < std::max<size_t>(src->instances.size(), 1); ++i) {
            ModelObject* o = m_model->add_object(*src);
            for (size_t k = o->instances.size(); k-- > 0;)
                if (k != i)
                    o->delete_instance(k);
            if (o->instances.empty())
                o->add_instance();
            o->invalidate_bounding_box();
            o->ensure_on_bed();
            ids.push_back(o->id().id);
        }
    }
    m_model->get_wipe_tower_vector() = loaded.get_wipe_tower_vector();
    m_model->get_custom_gcode_per_print_z_vector() = loaded.get_custom_gcode_per_print_z_vector();
    m_wipe_tower.x = float(m_model->wipe_tower().position.x());
    m_wipe_tower.y = float(m_model->wipe_tower().position.y());
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();

    json out{ {"ids", ids} };
    if (!config.empty()) {
        json values = json::object();
        for (const std::string& key : config.keys())
            values[key] = config.opt_serialize(key);
        out["config"] = values;
    }
    return out.dump();
}

// ------------------------------------------------------------------- painting

namespace {

// Triangles of each paint state, three vertices each, in object coordinates.
using StateTriangles = std::map<int, std::vector<float>>;

void add_facets(StateTriangles& out, int state, const indexed_triangle_set& its, const Transform3d& volume_matrix)
{
    if (its.indices.empty())
        return;
    std::vector<float>& v = out[state];
    v.reserve(v.size() + its.indices.size() * 9);
    for (const stl_triangle_vertex_indices& f : its.indices)
        for (int k = 0; k < 3; ++k) {
            const Vec3f p = (volume_matrix * its.vertices[f[k]].cast<double>()).cast<float>();
            v.insert(v.end(), { p.x(), p.y(), p.z() });
        }
}

std::shared_ptr<const PaintMesh> make_paint_mesh(int mode, const StateTriangles& by_state)
{
    auto mesh = std::make_shared<PaintMesh>();
    mesh->mode = mode;
    for (const auto& [state, v] : by_state) {
        if (v.empty())
            continue;
        mesh->groups.push_back({ uint32_t(state), uint32_t(mesh->positions.size() / 3), uint32_t(v.size() / 3) });
        mesh->positions.insert(mesh->positions.end(), v.begin(), v.end());
    }
    return mesh->groups.empty() ? nullptr : mesh;
}

FacetsAnnotation& paint_facets(ModelVolume& volume, int mode)
{
    switch (mode) {
    case PaintColors:    return volume.mm_segmentation_facets;
    case PaintSeam:      return volume.seam_facets;
    case PaintFuzzySkin: return volume.fuzzy_skin_facets;
    default:             return volume.supported_facets;
    }
}

// Calls fn(facet index) for every facet of the volume tilted less than `angle_deg` from the bed and
// facing down, like PrusaSlicer's GLGizmoFdmSupports::select_facets_by_angle().
template<class Fn>
void for_each_overhang(const ModelVolume& volume, const Transform3d& instance_no_offset, float angle_deg, Fn&& fn)
{
    const float       threshold = float(M_PI) / 180.f * angle_deg;
    const Transform3d trafo     = instance_no_offset * volume.get_matrix_no_offset();
    const Vec3f       down      = (trafo.inverse() * (-Vec3d::UnitZ())).cast<float>().normalized();
    const Vec3f       limit     = (trafo.inverse() * Vec3d(std::sin(threshold), 0, -std::cos(threshold))).cast<float>().normalized();
    const float       dot_limit = limit.dot(down);
    const indexed_triangle_set& its = volume.mesh().its;
    for (size_t i = 0; i < its.indices.size(); ++i)
        if (its_face_normal(its, its.indices[i]).dot(down) > dot_limit)
            fn(int(i));
}

void used_states(const TriangleSelector::TriangleSplittingData& data, std::set<int>& out)
{
    for (size_t state = 1; state < data.used_states.size(); ++state)
        if (data.used_states[state])
            out.insert(int(state));
}

} // namespace

std::shared_ptr<const PaintMesh> Engine::color_paint_for(const ModelObject& object)
{
    uint64_t stamp = 0;
    bool     painted = false;
    for (const ModelVolume* v : object.volumes)
        if (v->is_model_part()) {
            stamp   = stamp * 1000003u + v->mm_segmentation_facets.timestamp();
            painted = painted || !v->mm_segmentation_facets.empty();
        }
    const uint64_t id = object.id().id;
    auto cached = std::find_if(m_paint_cache.begin(), m_paint_cache.end(), [id](const PaintCacheEntry& e) { return e.id == id; });
    if (!painted) {
        if (cached != m_paint_cache.end())
            m_paint_cache.erase(cached);
        return nullptr;
    }
    if (cached != m_paint_cache.end() && cached->stamp == stamp)
        return cached->mesh;

    StateTriangles by_state;
    for (const ModelVolume* v : object.volumes) {
        if (!v->is_model_part() || v->mm_segmentation_facets.empty())
            continue;
        TriangleSelector selector(v->mesh());
        selector.deserialize(v->mm_segmentation_facets.get_data(), false);
        std::set<int> states;
        used_states(v->mm_segmentation_facets.get_data(), states);
        for (int state : states)
            add_facets(by_state, state, selector.get_facets(TriangleStateType(state)), v->get_matrix());
    }
    auto mesh = make_paint_mesh(PaintColors, by_state);
    if (cached != m_paint_cache.end())
        *cached = { id, stamp, mesh };
    else
        m_paint_cache.push_back({ id, stamp, mesh });
    return mesh;
}

void Engine::update_paint_mesh(PaintSession& session)
{
    StateTriangles by_state;
    for (size_t i = 0; i < session.selectors.size(); ++i)
        for (int state : session.states)
            add_facets(by_state, state, session.selectors[i]->get_facets(TriangleStateType(state)), session.volumes[i]->get_matrix());
    if (!session.highlight.empty())
        by_state[int(PaintHighlightState)] = session.highlight;
    session.mesh = make_paint_mesh(session.mode, by_state);
}

void Engine::end_paint_of(uint64_t id)
{
    if (m_paint && m_paint->object_id == id)
        m_paint.reset();
    m_paint_cache.erase(std::remove_if(m_paint_cache.begin(), m_paint_cache.end(),
                                       [id](const PaintCacheEntry& e) { return e.id == id; }),
                        m_paint_cache.end());
}

bool Engine::paint_begin(uint64_t id, int mode)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_paint.reset();
    ModelObject* o = find_object(id);
    if (o == nullptr || o->instances.empty())
        return false;
    auto session = std::make_unique<PaintSession>();
    session->object_id = id;
    session->mode      = std::clamp(mode, int(PaintSupports), int(PaintFuzzySkin));
    for (ModelVolume* v : o->volumes) {
        if (!v->is_model_part())
            continue;
        const FacetsAnnotation& facets = paint_facets(*v, session->mode);
        auto selector = std::make_unique<TriangleSelector>(v->mesh());
        selector->deserialize(facets.get_data(), false);
        used_states(facets.get_data(), session->states);
        session->volumes.push_back(v);
        session->selectors.push_back(std::move(selector));
        session->raycasters.push_back(std::make_unique<AABBMesh>(v->mesh()));
    }
    if (session->volumes.empty())
        return false;
    update_paint_mesh(*session);
    session->before = current_state();
    m_paint = std::move(session);
    touch_plate();
    return true;
}

bool Engine::paint_at(const float camera[3], const float dir[3], const PaintParams& params, bool continue_stroke)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    if (s == nullptr)
        return false;
    ModelObject* o = find_object(s->object_id);
    if (o == nullptr || o->instances.empty()) {
        m_paint.reset();
        return false;
    }
    const Transform3d instance           = o->instances.front()->get_matrix();
    const Transform3d instance_no_offset = o->instances.front()->get_matrix_no_offset();
    const Vec3d eye(camera[0], camera[1], camera[2]);
    const Vec3d ray = Vec3d(dir[0], dir[1], dir[2]).normalized();

    // The closest hit over all model parts, in the hit part's mesh coordinates.
    int    best = -1;
    double best_distance = std::numeric_limits<double>::max();
    Vec3f  hit = Vec3f::Zero();
    int    facet = -1;
    for (size_t i = 0; i < s->volumes.size(); ++i) {
        const Transform3d trafo = instance * s->volumes[i]->get_matrix();
        const Transform3d inv   = trafo.inverse();
        const AABBMesh::hit_result h = s->raycasters[i]->query_ray_hit(inv * eye, (inv.linear() * ray).normalized());
        if (!h.is_hit())
            continue;
        const double distance = (trafo * h.position() - eye).norm();
        if (distance < best_distance) {
            best_distance = distance;
            best  = int(i);
            hit   = h.position().cast<float>();
            facet = h.face();
        }
    }
    if (best < 0) {
        s->last_volume = -1;
        return false;
    }
    if (!s->stroke) {
        // Remember the painting before this stroke for undo.
        std::vector<TriangleSelector::TriangleSplittingData> before;
        for (const auto& selector : s->selectors)
            before.push_back(selector->serialize());
        s->undo.push_back(std::move(before));
        if (s->undo.size() > 30)
            s->undo.erase(s->undo.begin());
        s->stroke = true;
    }

    TriangleSelector&       selector           = *s->selectors[best];
    const ModelVolume&      volume             = *s->volumes[best];
    const Transform3d       trafo              = instance * volume.get_matrix();
    const Transform3d       trafo_no_translate = instance_no_offset * volume.get_matrix_no_offset();
    const Vec3f             camera_in_mesh     = (trafo.inverse() * eye).cast<float>();
    const TriangleSelector::ClippingPlane no_clipping;
    // Fuzzy skin has a single state.
    const int               state     = s->mode == PaintFuzzySkin ? std::min(params.state, 1) : params.state;
    const TriangleStateType new_state = TriangleStateType(std::clamp(state, 0, 16));
    const float             radius    = std::max(params.radius, 0.1f);
    const auto              cursor    = params.sphere ? TriangleSelector::CursorType::SPHERE : TriangleSelector::CursorType::CIRCLE;
    // Overhang-only painting applies to supports; filaments go everywhere.
    const float overhangs = s->mode == PaintSupports ? std::clamp(params.overhang_angle, 0.f, 90.f) : 0.f;
    if (params.tool == PaintFill) {
        selector.seed_fill_select_triangles(hit, facet, trafo_no_translate, no_clipping, std::clamp(params.fill_angle, 0.f, 90.f),
                                            0.02f, overhangs, TriangleSelector::ForceReselection::YES);
        selector.seed_fill_apply_on_triangles(new_state);
    } else if (params.tool == PaintHeightRange) {
        selector.select_patch(facet, std::make_unique<TriangleSelector::HeightRange>(
                                  hit, volume.mesh().bounding_box(), std::clamp(params.height_range, 0.05f, 50.f), trafo, no_clipping),
                              new_state, trafo_no_translate, true, overhangs);
    } else if (continue_stroke && s->last_volume == best) {
        // A capsule from the previous dab, so fast strokes leave no gaps.
        selector.select_patch(s->last_facet, TriangleSelector::DoublePointCursor::cursor_factory(
                                  s->last_hit, hit, camera_in_mesh, radius, cursor, trafo, no_clipping),
                              new_state, trafo_no_translate, true, overhangs);
    } else {
        selector.select_patch(facet, TriangleSelector::SinglePointCursor::cursor_factory(
                                  hit, camera_in_mesh, radius, cursor, trafo, no_clipping),
                              new_state, trafo_no_translate, true, overhangs);
    }
    s->last_volume = best;
    s->last_hit    = hit;
    s->last_facet  = facet;
    if (state > 0)
        s->states.insert(state);
    update_paint_mesh(*s);
    touch_plate();
    return true;
}

bool Engine::paint_overhangs(int state, float angle)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    ModelObject*  o = s ? find_object(s->object_id) : nullptr;
    if (s == nullptr || s->stroke || o == nullptr || o->instances.empty() || angle <= 0.f)
        return false;
    std::vector<TriangleSelector::TriangleSplittingData> before;
    for (const auto& selector : s->selectors)
        before.push_back(selector->serialize());
    const Transform3d instance_no_offset = o->instances.front()->get_matrix_no_offset();
    const TriangleStateType new_state = TriangleStateType(std::clamp(state, 0, 16));
    bool painted = false;
    for (size_t i = 0; i < s->volumes.size(); ++i)
        for_each_overhang(*s->volumes[i], instance_no_offset, std::min(angle, 90.f), [&](int facet) {
            s->selectors[i]->set_facet(facet, new_state);
            painted = true;
        });
    if (!painted)
        return false;
    s->undo.push_back(std::move(before));
    if (state > 0)
        s->states.insert(state);
    for (size_t i = 0; i < s->volumes.size(); ++i)
        paint_facets(*s->volumes[i], s->mode).set(*s->selectors[i]);
    update_paint_mesh(*s);
    touch_plate();
    return true;
}

void Engine::paint_highlight_overhangs(float angle)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    ModelObject*  o = s ? find_object(s->object_id) : nullptr;
    if (s == nullptr || o == nullptr || o->instances.empty() || angle == s->highlight_angle)
        return;
    s->highlight_angle = angle;
    s->highlight.clear();
    if (angle > 0.f) {
        const Transform3d instance_no_offset = o->instances.front()->get_matrix_no_offset();
        for (const ModelVolume* v : s->volumes) {
            const indexed_triangle_set& its = v->mesh().its;
            const Transform3d m = v->get_matrix();
            for_each_overhang(*v, instance_no_offset, std::min(angle, 90.f), [&](int facet) {
                for (int k = 0; k < 3; ++k) {
                    const Vec3f p = (m * its.vertices[its.indices[facet][k]].cast<double>()).cast<float>();
                    s->highlight.insert(s->highlight.end(), { p.x(), p.y(), p.z() });
                }
            });
        }
    }
    update_paint_mesh(*s);
    touch_plate();
}

void Engine::paint_commit()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    if (s == nullptr)
        return;
    for (size_t i = 0; i < s->volumes.size(); ++i)
        paint_facets(*s->volumes[i], s->mode).set(*s->selectors[i]);
    s->stroke      = false;
    s->last_volume = -1;
    touch_plate();
}

void Engine::paint_cancel_stroke()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    if (s == nullptr || !s->stroke)
        return;
    for (size_t i = 0; i < s->selectors.size(); ++i)
        s->selectors[i]->deserialize(s->undo.back()[i], true);
    s->undo.pop_back();
    s->stroke      = false;
    s->last_volume = -1;
    update_paint_mesh(*s);
    touch_plate();
}

bool Engine::paint_undo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    if (s == nullptr || s->stroke || s->undo.empty())
        return false;
    for (size_t i = 0; i < s->selectors.size(); ++i) {
        s->selectors[i]->deserialize(s->undo.back()[i], true);
        paint_facets(*s->volumes[i], s->mode).set(*s->selectors[i]);
    }
    s->undo.pop_back();
    update_paint_mesh(*s);
    touch_plate();
    return true;
}

void Engine::paint_clear()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PaintSession* s = m_paint.get();
    if (s == nullptr || s->stroke)
        return;
    std::vector<TriangleSelector::TriangleSplittingData> before;
    for (const auto& selector : s->selectors)
        before.push_back(selector->serialize());
    s->undo.push_back(std::move(before));
    for (size_t i = 0; i < s->selectors.size(); ++i) {
        s->selectors[i]->reset();
        paint_facets(*s->volumes[i], s->mode).set(*s->selectors[i]);
    }
    update_paint_mesh(*s);
    touch_plate();
}

void Engine::paint_end()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (PaintSession* s = m_paint.get(); s != nullptr) {
        if (s->stroke)
            for (size_t i = 0; i < s->volumes.size(); ++i)
                paint_facets(*s->volumes[i], s->mode).set(*s->selectors[i]);
        // The whole session is one step of the plate's undo, if it painted anything.
        if (!s->undo.empty() && s->before) {
            m_undo.push_back(std::move(*s->before));
            if (m_undo.size() > UndoLimit)
                m_undo.erase(m_undo.begin());
            m_redo.clear();
        }
    }
    m_paint.reset();
    touch_plate();
}

std::string Engine::paint_state_json()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const PaintSession* s = m_paint.get();
    if (s == nullptr)
        return json{ {"active", false} }.dump();
    return json{
        {"active", true},
        {"object", s->object_id},
        {"mode", s->mode},
        {"can_undo", !s->undo.empty()},
        {"painted", s->mesh != nullptr},
    }.dump();
}

// ------------------------------------------------------------------- slicing

void Engine::cancel_slice()
{
    if (Print* print = m_print.load())
        print->cancel();
}

std::string Engine::slice(const SliceRequest& request, const ProgressFn& progress)
{
    std::lock_guard<std::mutex> slice_lock(m_slice_mutex);
    json result;
    result["ok"] = false;

    Print print;
    std::vector<PlateObject> thumbnail_scene;
    try {
        DynamicPrintConfig config;
        Model model;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            config = resolve_config(request.printer, request.print, request.filaments);
        }
        for (const auto& [key, value] : request.overrides) {
            ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
            config.set_deserialize(key, value, ctx);
        }
        config.normalize_fdm();
        if (std::string err = config.validate(); !err.empty())
            throw RuntimeError(err);
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            // Variable layer heights for the settings of this slice; they stay with the plate for projects.
            update_layer_profiles(config);
            model = *m_model;
        }

        model.update_print_volume_state(build_volume(m_bed));
        size_t printable = 0;
        for (const ModelObject* o : model.objects)
            for (const ModelInstance* i : o->instances)
                printable += i->is_printable() ? 1 : 0;
        if (printable == 0)
            throw RuntimeError(model.objects.empty() ? "The plate is empty." :
                               "No object is fully inside the print volume.");

        thumbnail_scene = plate_snapshot();

        print.set_status_callback([&progress](const PrintBase::SlicingStatus& s) {
            if (s.percent >= 0 && progress)
                progress(s.percent, s.text);
        });
        for (ModelObject* mo : model.objects)
            print.auto_assign_extruders(mo);

        MultipleBedsUtils::with_single_bed_model_fff(model, 0, [&print, &model, &config]() {
            print.apply(model, config);
        });

        std::vector<std::string> warnings;
        if (std::string err = print.validate(&warnings); !err.empty())
            throw RuntimeError(err);
        result["warnings"] = warnings;

        m_print.store(&print);
        print.process();

        GCodeProcessorResult gcode_result;
        const BedShape bed = this->bed();
        ThumbnailsGeneratorCallback thumbnails = [&thumbnail_scene, &bed](const ThumbnailsParams& params) {
            return render_thumbnails(thumbnail_scene, bed, params);
        };
        if (progress)
            progress(95, "Exporting G-code");
        std::string written = print.export_gcode(request.output_path, &gcode_result, thumbnails);
        m_print.store(nullptr);

        const PrintStatistics& stats = print.print_statistics();
        // The file name template may use statistics such as [print_time], known only now.
        if (const std::string final_path = stats.finalize_output_path(written); final_path != written) {
            boost::system::error_code ec;
            boost::filesystem::rename(written, final_path, ec);
            if (!ec)
                written = final_path;
        }
        result["ok"]           = true;
        result["path"]         = written;
        result["print_time"]   = stats.estimated_normal_print_time;
        result["print_time_s"] = stats.normal_print_time_seconds;
        result["filament_mm"]  = stats.total_used_filament;
        result["filament_cm3"] = stats.total_extruded_volume / 1000.;
        result["filament_g"]   = stats.total_weight;
        result["cost"]         = stats.total_cost;

        if (gcode_result.conflict_result) {
            const auto& c = *gcode_result.conflict_result;
            warnings.push_back("Toolpaths of \"" + c._objName1 + "\" and \"" + c._objName2 + "\" collide at " +
                               float_to_string_decimal_point(c._height, 2) + " mm. Move the objects further apart.");
            result["warnings"] = warnings;
        }

        // Tool colors as in PrusaSlicer: the extruder color, or the filament color when that is empty.
        const auto& extruder_colors = config.option<ConfigOptionStrings>("extruder_colour")->values;
        const auto& filament_colors = config.option<ConfigOptionStrings>("filament_colour")->values;
        const size_t extruders = config.option<ConfigOptionFloats>("nozzle_diameter")->values.size();
        std::vector<std::string> colors;
        for (size_t i = 0; i < extruders; ++i) {
            const std::string e = i < extruder_colors.size() ? extruder_colors[i] : std::string();
            const std::string f = filament_colors.empty() ? std::string() : filament_colors[std::min(i, filament_colors.size() - 1)];
            colors.push_back(e.empty() ? f : e);
        }
        if (print.has_wipe_tower() && !print.wipe_tower_data().tool_changes.empty()) {
            const WipeTowerData& wt = print.wipe_tower_data();
            result["wipe_tower"] = { {"depth", wt.depth}, {"brim", wt.brim_width}, {"height", wt.height} };
        }
        std::vector<int> used;
        json slots = json::array();
        const auto& diameters = config.option<ConfigOptionFloats>("filament_diameter")->values;
        const auto& densities = config.option<ConfigOptionFloats>("filament_density")->values;
        for (const auto& [extruder, length] : print.print_statistics().filament_stats) {
            used.push_back(int(extruder) + 1);
            // Grams from the length, as PrusaSlicer's estimate does: cross-section times density.
            const double d = diameters.empty() ? 1.75 : diameters[std::min(size_t(extruder), diameters.size() - 1)];
            const double density = densities.empty() ? 0. : densities[std::min(size_t(extruder), densities.size() - 1)];
            const double cm3 = length * M_PI * d * d / 4. / 1000.;
            slots.push_back({ {"extruder", int(extruder) + 1}, {"mm", length}, {"g", cm3 * density} });
        }
        result["extruders_used"] = used;
        result["slots"] = slots;

        // Per layer: the time it takes, and where the filaments printed change, for markers on the layer slider.
        std::vector<float> layer_times;
        std::vector<std::set<int>> layer_tools;
        for (const GCodeProcessorResult::MoveVertex& move : gcode_result.moves) {
            if (move.layer_id >= layer_times.size()) {
                layer_times.resize(move.layer_id + 1, 0.f);
                layer_tools.resize(move.layer_id + 1);
            }
            layer_times[move.layer_id] += move.time[size_t(PrintEstimatedStatistics::ETimeMode::Normal)];
            if (move.type == EMoveType::Extrude && move.delta_extruder > 0.f)
                layer_tools[move.layer_id].insert(int(move.extruder_id) + 1);
        }
        json changes = json::array();
        const std::set<int>* previous = nullptr;
        for (size_t layer = 0; layer < layer_tools.size(); ++layer) {
            if (layer_tools[layer].empty())
                continue;
            if (previous != nullptr && *previous != layer_tools[layer])
                changes.push_back({ {"layer", layer}, {"tools", layer_tools[layer]} });
            previous = &layer_tools[layer];
        }
        result["layer_times"]      = layer_times;
        result["filament_changes"] = changes;
        auto preview = make_preview(gcode_result, colors);
        result["layers"] = preview_layer_zs(*preview);
        {
            std::lock_guard<std::mutex> lock(m_preview_mutex);
            m_preview = std::move(preview);
            ++m_preview_revision;
        }
    } catch (const CanceledException&) {
        m_print.store(nullptr);
        result["canceled"] = true;
        result["error"]    = "Slicing was canceled.";
    } catch (const std::exception& ex) {
        m_print.store(nullptr);
        result["error"] = ex.what();
    }
    return result.dump();
}

std::shared_ptr<libvgcode::GCodeInputData> Engine::preview(uint64_t& revision) const
{
    std::lock_guard<std::mutex> lock(m_preview_mutex);
    revision = m_preview_revision;
    return m_preview;
}

void Engine::clear_preview()
{
    std::lock_guard<std::mutex> lock(m_preview_mutex);
    m_preview.reset();
    ++m_preview_revision;
}

} // namespace andslice

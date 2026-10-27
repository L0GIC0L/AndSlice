#include "Engine.hpp"

#include "GCodePreview.hpp"
#include "Thumbnail.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>
#include <LocalesUtils.hpp>
#include <nlohmann/json.hpp>

#include <arrange-wrapper/ArrangeSettingsView.hpp>
#include <arrange-wrapper/ModelArrange.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/BuildVolume.hpp"
#include "libslic3r/FileReader.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/MultipleBeds.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

using nlohmann::json;
using namespace Slic3r;

namespace andslice {

Engine& Engine::get()
{
    static Engine engine;
    return engine;
}

Engine::Engine() : m_model(std::make_unique<Model>()) {}
Engine::~Engine() = default;

void Engine::init(const std::string& data_dir, const std::string& resources_dir, const std::string& bundle_path)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    set_logging_level(2); // warnings and errors only
    set_data_dir(data_dir);
    set_resources_dir(resources_dir);
    boost::filesystem::create_directories(data_dir);

    // Start from an empty plate; init runs once per app process (or per test).
    m_model->clear_objects();
    m_meshes.clear();
    m_selected = -1;
    touch_plate();
    {
        std::lock_guard<std::mutex> preview_lock(m_preview_mutex);
        m_preview.reset();
        ++m_preview_revision;
    }

    auto bundle = std::make_unique<PresetBundle>();
    bundle->set_default_suppressed(true);
    bundle->load_configbundle(bundle_path, PresetBundle::LoadConfigBundleAttribute::LoadSystem,
                              ForwardCompatibilitySubstitutionRule::EnableSystemSilent);
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
    // The default printer is the first variant of the first model, as in PrusaSlicer's wizard.
    std::string printer = first_visible(b.printers);
    if (!b.vendors.empty() && !b.vendors.begin()->second.models.empty()) {
        const VendorProfile::PrinterModel& model = b.vendors.begin()->second.models.front();
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
    out["filaments"] = preset_names(m_bundle->filaments, true);
    out["default_print"]    = selected.config.opt_string("default_print_profile");
    const auto* def_fil = selected.config.option<ConfigOptionStrings>("default_filament_profile");
    out["default_filament"] = def_fil && !def_fil->values.empty() ? def_fil->values.front() : std::string();
    return out.dump();
}

DynamicPrintConfig Engine::resolve_config(const std::string& printer, const std::string& print, const std::string& filament)
{
    PresetBundle& b = *m_bundle;
    if (!b.printers.select_preset_by_name(printer, true))
        throw RuntimeError("Unknown printer profile: " + printer);
    b.update_compatible(PresetSelectCompatibleType::Always);
    if (!b.prints.select_preset_by_name(print, true))
        throw RuntimeError("Unknown print profile: " + print);
    if (!b.filaments.select_preset_by_name(filament, true))
        throw RuntimeError("Unknown filament profile: " + filament);
    for (ExtruderFilaments& ef : b.extruders_filaments)
        ef.select_filament(filament);
    return b.full_config();
}

std::string Engine::config_json(const std::string& printer, const std::string& print, const std::string& filament)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DynamicPrintConfig config = resolve_config(printer, print, filament);
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

    indexed_triangle_set its = object.raw_indexed_triangle_set();
    auto mesh = std::make_shared<MeshData>();
    mesh->positions.reserve(its.vertices.size() * 3);
    mesh->min = { FLT_MAX, FLT_MAX, FLT_MAX };
    mesh->max = { -FLT_MAX, -FLT_MAX, -FLT_MAX };
    for (const stl_vertex& v : its.vertices) {
        for (int i = 0; i < 3; ++i) {
            mesh->positions.push_back(v[i]);
            mesh->min[i] = std::min(mesh->min[i], v[i]);
            mesh->max[i] = std::max(mesh->max[i], v[i]);
        }
    }
    mesh->indices.reserve(its.indices.size() * 3);
    for (const stl_triangle_vertex_indices& f : its.indices)
        for (int i = 0; i < 3; ++i)
            mesh->indices.push_back(uint32_t(f[i]));
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
        arr2::ArrangeSettings settings;
        settings.set_distance_from_objects(m_arrange_distance);
        arrange_objects(*m_model, arr2::to_arrange_bed(bed_points(m_bed), Vec2crd{0, 0}), settings);
    }
    m_model->update_print_volume_state(build_volume(m_bed));
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
        m_model->delete_object(o);
        m_meshes.erase(std::remove_if(m_meshes.begin(), m_meshes.end(),
                                      [id](const auto& m) { return m.first == id; }),
                       m_meshes.end());
        if (m_selected == int64_t(id))
            m_selected = -1;
        touch_plate();
    }
}

void Engine::clear_plate()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_model->clear_objects();
    m_meshes.clear();
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
    arr2::ArrangeSettings settings;
    settings.set_distance_from_objects(m_arrange_distance);
    arrange_objects(*m_model, arr2::to_arrange_bed(bed_points(m_bed), Vec2crd{0, 0}), settings);
    m_model->update_print_volume_state(build_volume(m_bed));
    touch_plate();
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
        out.push_back({
            {"id", o->id().id},
            {"name", o->name},
            {"offset", {off.x(), off.y(), off.z()}},
            {"rotation", {rad2deg(rot.x()), rad2deg(rot.y()), rad2deg(rot.z())}},
            {"scale", {scale.x(), scale.y(), scale.z()}},
            {"size", {bb.size().x(), bb.size().y(), bb.size().z()}},
            {"triangles", o->facets_count()},
            {"outside", inst->print_volume_state != ModelInstancePVS_Inside},
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
        const Transform3f m = o->instances.front()->get_matrix().cast<float>();
        std::copy(m.data(), m.data() + 16, po.transform.begin());
        out.push_back(std::move(po));
    }
    return out;
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
            config = resolve_config(request.printer, request.print, request.filament);
            model  = *m_model;
        }
        for (const auto& [key, value] : request.overrides) {
            ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
            config.set_deserialize(key, value, ctx);
        }
        config.normalize_fdm();
        if (std::string err = config.validate(); !err.empty())
            throw RuntimeError(err);

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

        std::vector<std::string> colors = config.option<ConfigOptionStrings>("extruder_colour")->values;
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

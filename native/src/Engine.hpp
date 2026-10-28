// AndSlice engine: owns the PrusaSlicer objects (preset bundle, plate model,
// print) and exposes the operations the Android UI needs. All public methods
// are thread safe; slicing runs on the caller's thread and can be cancelled
// from any other thread.
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace Slic3r {
class Model;
class ModelObject;
class Print;
class PresetBundle;
class DynamicPrintConfig;
}

namespace libvgcode {
struct GCodeInputData;
}

namespace andslice {

// Indexed triangle mesh in object coordinates, shared with the renderer.
struct MeshData {
    std::vector<float>    positions; // x, y, z per vertex
    std::vector<uint32_t> indices;   // three per triangle
    std::array<float, 3>  min{}, max{};
    // {first index, index count} of each model part, in the object's volume order.
    std::vector<std::array<uint32_t, 2>> parts;
};

// What a paint session paints on an object's surface.
enum PaintMode {
    PaintSupports  = 0, // states: 1 enforce supports, 2 block supports
    PaintColors    = 1, // states: n paints with extruder (ACE slot) n
    PaintSeam      = 2, // states: 1 seams go here, 2 seams stay away
    PaintFuzzySkin = 3, // states: 1 fuzzy skin
};
enum PaintTool {
    PaintBrush       = 0, // circle or sphere brush
    PaintFill        = 1, // smart fill of the connected area up to edges sharper than fill_angle
    PaintHeightRange = 2, // a horizontal band of height_range around the touched height
};

// How a dab paints, as in PrusaSlicer's painting gizmos.
struct PaintParams {
    int   tool           = PaintBrush;
    int   state          = 1;     // see PaintMode; 0 erases
    float radius         = 2.f;   // brush radius, mm
    bool  sphere         = false; // a sphere also paints faces turned away from the camera
    float fill_angle     = 30.f;  // smart fill: largest angle between neighboring faces, degrees
    float height_range   = 1.f;   // height range: height of the band, mm
    float overhang_angle = 0.f;   // > 0: paint only overhangs tilted less than this from the bed, degrees
};

// Paint state of the overhang highlight in PaintMesh::groups.
constexpr uint32_t PaintHighlightState = 255;

// Painted facets of an object in object coordinates, for the renderer.
struct PaintMesh {
    int                                  mode = PaintSupports;
    std::vector<float>                   positions; // x, y, z; three vertices per triangle, grouped by state
    std::vector<std::array<uint32_t, 3>> groups;    // {state, first vertex, vertex count}
};

// One object on the plate as the renderer and the thumbnail generator see it.
struct PlateObject {
    uint64_t                         id = 0;
    std::shared_ptr<const MeshData>  mesh;
    std::array<float, 16>            transform{}; // column-major instance matrix
    bool                             selected = false;
    bool                             outside  = false;
    std::vector<int>                 part_extruders; // 1-based extruder of each mesh part
    std::shared_ptr<const PaintMesh> paint;          // painted facets to draw over the mesh, or null
};

// Footprint of the wipe (prime) tower on the bed; empty when no tower is printed.
struct WipeTowerShape {
    bool  visible = false;
    float x = 0.f, y = 0.f;          // front left corner, mm
    float width = 0.f, depth = 0.f;  // without the brim
    float brim = 0.f;
    float height = 0.f;
};

// Where a cut would go: a horizontal plane across an object's footprint.
struct CutPlane {
    bool  visible = false;
    float z = 0.f;
    float x0 = 0.f, y0 = 0.f, x1 = 0.f, y1 = 0.f;
};

struct BedShape {
    std::vector<std::array<float, 2>> outline; // bed polygon in mm
    float                             max_height = 0.f;
};

struct SliceRequest {
    std::string printer;
    std::string print;
    // Filament preset of each extruder (ACE slot); the last one repeats for missing extruders.
    std::vector<std::string> filaments;
    // Option key -> PrusaSlicer serialized value, applied over the presets.
    std::vector<std::pair<std::string, std::string>> overrides;
    std::string output_path;
};

using ProgressFn = std::function<void(int percent, const std::string& message)>;

class Engine {
public:
    static Engine& get();

    // Sets PrusaSlicer's data/resources directories, empties the plate and loads the profiles (see load_profiles).
    void init(const std::string& data_dir, const std::string& resources_dir, const std::vector<std::string>& bundles);
    // Loads the given vendor config bundles as system presets (the first one is the default vendor),
    // then the user presets saved in the data directory. The plate is kept.
    void load_profiles(const std::vector<std::string>& bundles);

    // ---------------------------------------------------------------- profiles
    // {"printers":[...], "prints":[...], "filaments":[...], "default_print":..., "default_filament":...,
    //  "extruders": N, "template_filaments": [...], "user": {"print": [...], "filament": [...], "printer": [...]},
    //  "vendor": ..., "model": ..., "variant": ..., "variants": [{"variant", "preset"}]}
    // where prints and filaments are the presets compatible with `printer`, and variants are the
    // nozzle variants of the printer's model.
    std::string presets_json(const std::string& printer);
    // Name of the system printer preset of a vendor's model and nozzle variant, or "" if not loaded.
    std::string printer_for_model(const std::string& model, const std::string& variant);
    // Saves the selected preset of `type` ("print", "filament" or "printer") with the overrides
    // that belong to it as the user preset `name`. Returns the preset name.
    std::string save_preset(const std::string& type, const std::string& name, const std::string& printer,
                            const std::string& print, const std::vector<std::string>& filaments,
                            const std::vector<std::pair<std::string, std::string>>& overrides);
    // Deletes a user preset and its file.
    void        delete_preset(const std::string& type, const std::string& name);
    // Fully resolved configuration (key -> serialized value) for the printer, print and per-extruder filaments.
    std::string config_json(const std::string& printer, const std::string& print, const std::vector<std::string>& filaments);
    // Metadata of every option the settings screen can edit, grouped by preset type.
    std::string option_defs_json();
    // Selects the printer whose bed is drawn and used for placement.
    void set_printer(const std::string& printer);
    BedShape bed() const;

    // ---------------------------------------------------------------- plate
    // Gap kept between objects by arrange and automatic placement, in mm.
    void        set_arrange_distance(float mm);
    // Loads STL/OBJ/3MF/AMF and places the objects on the bed. Returns JSON with the new object ids.
    std::string add_model(const std::string& path, const std::string& display_name);
    void        remove_object(uint64_t id);
    void        clear_plate();
    uint64_t    duplicate_object(uint64_t id);
    // t = {offset x, offset y, rotation x, y, z (degrees), scale x, y, z}. Z offset keeps the object on the bed.
    void        set_transform(uint64_t id, const double t[8]);
    // Rotates the object so the face hit by the given world-space ray lies on the bed.
    bool        lay_on_face(uint64_t id, const float origin[3], const float dir[3]);
    void        arrange();
    void        center_object(uint64_t id);
    // Assigns a 1-based extruder (ACE slot) to a whole object (part < 0) or to one of its model parts.
    // Assigning the whole object also clears the per-part choices.
    void        set_extruder(uint64_t id, int part, int extruder);
    // Mirrors the object along a world axis (0 X, 1 Y, 2 Z), whatever its rotation.
    void        mirror_object(uint64_t id, int axis);
    // Splits the object where its mesh is not connected: into separate objects, or into parts of the
    // object, which then get filament slots in turn. Returns {"ids": [...]} of the objects, empty
    // when there was nothing to split.
    std::string split_object(uint64_t id, bool into_parts);
    // Cuts the object across at `height` mm above the bed, as PrusaSlicer's cut tool with a plane.
    // The upper part can be turned over to stand on its cut face. Returns {"ids": [...]}.
    std::string cut_object(uint64_t id, double height, bool keep_upper, bool keep_lower, bool upper_on_cut);
    // Shows where a cut at `height` would go across the object; a height below 0 hides it.
    void        set_cut_plane(uint64_t id, double height);
    CutPlane    cut_plane() const;
    // Wipe tower placement used for arranging and slicing; the UI decides whether one is printed.
    void        set_wipe_tower(const WipeTowerShape& shape);
    WipeTowerShape wipe_tower() const;
    std::string objects_json();
    void        select(int64_t id);
    // Returns the id of the closest object hit by the world-space ray, or -1.
    int64_t     pick(const float origin[3], const float dir[3]);

    // ---------------------------------------------------------------- per-object settings
    // Variable layer height with PrusaSlicer's adaptive algorithm: thin layers on slopes and curves, thick ones
    // on walls, smoothed. `quality` is PrusaSlicer's quality/speed factor: 0 gives the thinnest layers, 1 the
    // thickest. It is worked out again at every slice, so it follows the object's size and the settings.
    // A quality below 0 turns it off.
    void        set_layer_quality(uint64_t id, float quality);
    // An object's own settings over the print profile:
    // {"options": {key: value}, "ranges": [{"min", "max", "options": {...}}], "layer_quality": q or -1,
    //  "object_keys": [...], "range_keys": [...]}, where the keys are the options an object or a height range takes.
    std::string object_settings_json(uint64_t id);
    // Sets an option of the object, or of its height range starting at `range_min` when that is >= 0.
    // An empty value removes the option.
    void        set_object_option(uint64_t id, double range_min, const std::string& key, const std::string& value);
    // Adds, moves or (with min >= max) removes the height range that starts at `old_min` (< 0 for a new one).
    // A new range starts with the layer height the object prints with now.
    void        edit_height_range(uint64_t id, double old_min, double min_z, double max_z);

    // ---------------------------------------------------------------- projects
    // Saves the plate, with painting and transforms, as a PrusaSlicer 3MF project, which PrusaSlicer
    // and OrcaSlicer open too. The settings (presets plus overrides) are stored with it.
    void        save_project(const std::string& path, const SliceRequest& settings);
    // Replaces the plate with the objects of a 3MF project, where the project put them.
    // Returns {"ids": [...], "config": {key: serialized value}}; "config" is missing for files without settings.
    std::string open_project(const std::string& path);

    // ---------------------------------------------------------------- painting
    // Starts painting an object's model parts in the given PaintMode, with PrusaSlicer's
    // TriangleSelector, as its support and multi-material painting gizmos do.
    bool        paint_begin(uint64_t id, int mode);
    // Paints where the world-space ray from the camera position first hits the object.
    // With PaintBrush, `continue_stroke` joins this dab to the previous one of the stroke.
    // Returns whether the object was hit.
    bool        paint_at(const float camera[3], const float dir[3], const PaintParams& params, bool continue_stroke);
    // Paints `state` on every overhang tilted less than `angle` degrees from the bed, as PrusaSlicer's
    // "Enforce/Block supports by angle" does. Returns whether any face was painted.
    bool        paint_overhangs(int state, float angle);
    // Shows the overhangs tilted less than `angle` degrees from the bed over the object; 0 hides them.
    void        paint_highlight_overhangs(float angle);
    // Ends the stroke and stores the painting in the model, where slicing uses it.
    void        paint_commit();
    // Takes back the stroke in progress, e.g. when a second finger turns it into a camera gesture.
    void        paint_cancel_stroke();
    bool        paint_undo();
    void        paint_clear();
    void        paint_end();
    // {"active": bool, "object": id, "mode": int, "can_undo": bool, "painted": bool}
    std::string paint_state_json();

    // ---------------------------------------------------------------- undo
    // Remembers the plate before a change the user makes, for undo, and forgets what was undone.
    // A paint session remembers the plate itself, once something is painted.
    void        push_undo();
    bool        undo();
    bool        redo();
    // {"can_undo": bool, "can_redo": bool}
    std::string undo_state_json();

    // Monotonic counter bumped by every plate change, so the renderer knows when to re-read.
    uint64_t                 plate_revision() const { return m_plate_revision.load(); }
    std::vector<PlateObject> plate_snapshot();

    // ---------------------------------------------------------------- slicing
    // Slices the plate and writes G-code. Returns a JSON result (see Engine.cpp).
    std::string slice(const SliceRequest& request, const ProgressFn& progress);
    void        cancel_slice();

    // Latest G-code preview data for libvgcode; `revision` tells whether it changed.
    std::shared_ptr<libvgcode::GCodeInputData> preview(uint64_t& revision) const;
    void        clear_preview();

private:
    Engine();
    ~Engine();

    Slic3r::DynamicPrintConfig resolve_config(const std::string& printer, const std::string& print,
                                              const std::vector<std::string>& filaments);
    void                       load_profiles_locked(const std::vector<std::string>& bundles);
    void                       arrange_plate();
    Slic3r::ModelObject*       find_object(uint64_t id);
    void                       place_new_objects(size_t first_new);
    void                       touch_plate();
    std::shared_ptr<const MeshData> mesh_for(const Slic3r::ModelObject& object);
    std::shared_ptr<const PaintMesh> color_paint_for(const Slic3r::ModelObject& object);
    struct PaintSession;
    // The plate as undo and redo keep it.
    struct PlateState;
    PlateState                 current_state() const;
    void                       restore_state(PlateState&& state);
    void                       update_paint_mesh(PaintSession& session);
    void                       end_paint_of(uint64_t id);
    void                       update_layer_profiles(const Slic3r::DynamicPrintConfig& config);

    mutable std::mutex                     m_mutex;        // guards everything below except m_print
    std::unique_ptr<Slic3r::PresetBundle>  m_bundle;
    std::string                            m_data_dir;
    std::unique_ptr<Slic3r::Model>         m_model;
    BedShape                               m_bed;
    int64_t                                m_selected = -1;
    float                                  m_arrange_distance = 6.f;
    WipeTowerShape                         m_wipe_tower;
    CutPlane                               m_cut_plane;
    std::vector<std::pair<uint64_t, std::shared_ptr<const MeshData>>> m_meshes;
    std::atomic<uint64_t>                  m_plate_revision{1};
    std::unique_ptr<PaintSession>          m_paint;
    // Filament painting of objects that are not being painted: {object id, paint timestamp, mesh}.
    struct PaintCacheEntry {
        uint64_t                         id = 0;
        uint64_t                         stamp = 0;
        std::shared_ptr<const PaintMesh> mesh;
    };
    std::vector<PaintCacheEntry>           m_paint_cache;
    // Variable layer height quality of objects that have it, by object id.
    std::map<uint64_t, float>              m_layer_quality;
    std::vector<PlateState>                m_undo, m_redo;

    std::mutex                             m_slice_mutex;  // one slice at a time
    std::atomic<Slic3r::Print*>            m_print{nullptr};

    mutable std::mutex                     m_preview_mutex;
    std::shared_ptr<libvgcode::GCodeInputData> m_preview;
    uint64_t                               m_preview_revision = 0;
};

} // namespace andslice

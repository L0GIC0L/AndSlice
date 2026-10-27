// AndSlice engine: owns the PrusaSlicer objects (preset bundle, plate model,
// print) and exposes the operations the Android UI needs. All public methods
// are thread safe; slicing runs on the caller's thread and can be cancelled
// from any other thread.
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
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
};

// One object on the plate as the renderer and the thumbnail generator see it.
struct PlateObject {
    uint64_t                         id = 0;
    std::shared_ptr<const MeshData>  mesh;
    std::array<float, 16>            transform{}; // column-major instance matrix
    bool                             selected = false;
    bool                             outside  = false;
};

struct BedShape {
    std::vector<std::array<float, 2>> outline; // bed polygon in mm
    float                             max_height = 0.f;
};

struct SliceRequest {
    std::string printer;
    std::string print;
    std::string filament;
    // Option key -> PrusaSlicer serialized value, applied over the presets.
    std::vector<std::pair<std::string, std::string>> overrides;
    std::string output_path;
};

using ProgressFn = std::function<void(int percent, const std::string& message)>;

class Engine {
public:
    static Engine& get();

    // Sets PrusaSlicer's data/resources directories and loads the system profile bundle.
    void init(const std::string& data_dir, const std::string& resources_dir, const std::string& bundle_path);

    // ---------------------------------------------------------------- profiles
    // {"printers":[...], "prints":[...], "filaments":[...], "default_print":..., "default_filament":...}
    // where prints and filaments are the presets compatible with `printer`.
    std::string presets_json(const std::string& printer);
    // Fully resolved configuration (key -> serialized value) for the preset triple.
    std::string config_json(const std::string& printer, const std::string& print, const std::string& filament);
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
    std::string objects_json();
    void        select(int64_t id);
    // Returns the id of the closest object hit by the world-space ray, or -1.
    int64_t     pick(const float origin[3], const float dir[3]);

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
                                              const std::string& filament);
    Slic3r::ModelObject*       find_object(uint64_t id);
    void                       place_new_objects(size_t first_new);
    void                       touch_plate();
    std::shared_ptr<const MeshData> mesh_for(const Slic3r::ModelObject& object);

    mutable std::mutex                     m_mutex;        // guards everything below except m_print
    std::unique_ptr<Slic3r::PresetBundle>  m_bundle;
    std::unique_ptr<Slic3r::Model>         m_model;
    BedShape                               m_bed;
    int64_t                                m_selected = -1;
    float                                  m_arrange_distance = 6.f;
    std::vector<std::pair<uint64_t, std::shared_ptr<const MeshData>>> m_meshes;
    std::atomic<uint64_t>                  m_plate_revision{1};

    std::mutex                             m_slice_mutex;  // one slice at a time
    std::atomic<Slic3r::Print*>            m_print{nullptr};

    mutable std::mutex                     m_preview_mutex;
    std::shared_ptr<libvgcode::GCodeInputData> m_preview;
    uint64_t                               m_preview_revision = 0;
};

} // namespace andslice

// OpenGL ES 3 renderer for the plate view (bed, build volume, objects) and the
// G-code preview. All methods run on the GLSurfaceView render thread.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>

#include "Engine.hpp"
#include "PreviewRenderer.hpp"

namespace andslice {

class SceneRenderer {
public:
    enum Mode { ModePlate = 0, ModePreview = 1 };

    static SceneRenderer& get();

    // Returns an error message if the G-code preview cannot be used on this GPU.
    std::string on_surface_created();
    void        on_surface_changed(int width, int height);
    void        draw(const float view[16], const float projection[16], int mode);

    PreviewRenderer& preview() { return m_preview; }

private:
    struct GpuMesh {
        unsigned vao = 0, vbo = 0, ibo = 0;
        int      index_count = 0;
        std::weak_ptr<const MeshData> source;
    };

    void sync_plate();
    void sync_bed();
    void sync_preview();
    void draw_bed(const float viewproj[16]);
    void draw_objects(const float viewproj[16], bool dimmed);

    unsigned m_mesh_program = 0;
    unsigned m_line_program = 0;
    int      m_width = 1, m_height = 1;

    std::map<uint64_t, GpuMesh> m_meshes;
    std::vector<PlateObject>    m_plate;
    uint64_t                    m_plate_revision = 0;

    unsigned m_bed_vao = 0, m_bed_vbo = 0;
    int      m_bed_fill_first = 0, m_bed_fill_count = 0;
    int      m_bed_grid_first = 0, m_bed_grid_count = 0;
    int      m_bed_edge_first = 0, m_bed_edge_count = 0;

    PreviewRenderer m_preview;
    uint64_t        m_preview_revision = 0;
    std::string     m_preview_error;
};

} // namespace andslice

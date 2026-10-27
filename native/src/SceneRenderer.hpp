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
    static constexpr int PaletteSize = 7;
    // RGBA colors: background, bed, grid, bed edges, object, selected object, object outside the bed.
    void        set_palette(const float colors[PaletteSize * 4]);

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

    enum PaletteEntry { Background, Bed, Grid, Edges, Object, Selected, Outside };
    // Dark defaults until the app sends its Material color scheme.
    float m_palette[PaletteSize][4] = {
        { 0.11f, 0.12f, 0.14f, 1.f }, { 0.22f, 0.23f, 0.26f, 1.f }, { 0.55f, 0.57f, 0.62f, 0.35f },
        { 0.75f, 0.77f, 0.82f, 0.6f }, { 1.0f, 0.86f, 0.3f, 1.f }, { 1.0f, 0.55f, 0.15f, 1.f }, { 0.35f, 0.55f, 0.95f, 1.f },
    };

    PreviewRenderer m_preview;
    uint64_t        m_preview_revision = 0;
    std::string     m_preview_error;
};

} // namespace andslice

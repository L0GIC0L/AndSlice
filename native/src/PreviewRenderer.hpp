// Draws the G-code preview with PrusaSlicer's libvgcode viewer (Android only).
#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

namespace libvgcode {
struct GCodeInputData;
class Viewer;
}

namespace andslice {

// Owns the libvgcode viewer. Every method must be called on the GL thread.
class PreviewRenderer {
public:
    PreviewRenderer();
    ~PreviewRenderer();

    // Call after an OpenGL ES 3 context became current, with glGetString(GL_VERSION).
    // Returns an error message or "".
    std::string init(const char* gl_version);
    // Drops all GL objects; call when the context was lost.
    void        release();
    bool        ready() const { return m_initialized; }

    void        load(const libvgcode::GCodeInputData& data);
    void        clear();
    bool        has_data() const { return m_loaded; }
    void        render(const float view[16], const float projection[16]);

    void        set_layer_range(uint32_t first, uint32_t last);
    void        set_view_type(int type);
    void        set_option_visible(int option, bool visible);
    void        set_role_visible(int role, bool visible);
    void        set_top_layer_only(bool enabled);
    // Moves 0..1 along the toolpaths of the top visible layer (1 = whole layer).
    void        set_move_fraction(float fraction);
    // Layer range and move fraction together, with one update of the viewer; nothing when unchanged.
    void        set_view(uint32_t first, uint32_t last, float fraction);
    // Draws each toolpath segment as its side facing the camera only, with a quarter of the vertices.
    void        set_low_detail(bool low_detail);

    // Legend and statistics of the loaded preview as JSON (thread safe, cached).
    std::string info_json() const;

private:
    void update_info();

    std::unique_ptr<libvgcode::Viewer> m_viewer;
    bool        m_initialized = false;
    bool        m_loaded      = false;
    float       m_move_fraction = 1.f;
    // Last requested layer range, re-applied when new data is loaded.
    uint32_t    m_first_layer = 0;
    uint32_t    m_last_layer  = UINT32_MAX;
    int         m_view_type   = 0;
    bool        m_travels     = false;
    bool        m_low_detail  = false;
    mutable std::mutex m_info_mutex;
    std::string m_info = "{}";
};

} // namespace andslice

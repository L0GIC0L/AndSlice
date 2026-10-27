#include "PreviewRenderer.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include <nlohmann/json.hpp>

#include <ColorRange.hpp>
#include <GCodeInputData.hpp>
#include <Types.hpp>
#include <Viewer.hpp>

namespace andslice {

using namespace libvgcode;

PreviewRenderer::PreviewRenderer() : m_viewer(std::make_unique<Viewer>()) {}
PreviewRenderer::~PreviewRenderer() = default;

std::string PreviewRenderer::init(const char* gl_version)
{
    if (m_initialized)
        return {};
    try {
        m_viewer->init(gl_version);
        m_initialized = true;
        return {};
    } catch (const std::exception& ex) {
        return ex.what();
    }
}

void PreviewRenderer::release()
{
    // The context that owned the GL objects is gone; start over with a fresh viewer.
    m_viewer = std::make_unique<Viewer>();
    m_initialized = false;
    m_loaded = false;
}

void PreviewRenderer::load(const GCodeInputData& data)
{
    GCodeInputData copy = data;
    m_viewer->reset_default_extrusion_roles_colors();
    m_viewer->set_view_type(EViewType(m_view_type));
    set_option_visible(int(EOptionType::Travels), m_travels);
    m_viewer->load(std::move(copy));
    m_loaded = !data.vertices.empty();
    set_layer_range(m_first_layer, m_last_layer);
    update_info();
}

void PreviewRenderer::clear()
{
    m_viewer->reset();
    m_loaded = false;
    update_info();
}

void PreviewRenderer::render(const float view[16], const float projection[16])
{
    if (!m_initialized || !m_loaded)
        return;
    Mat4x4 v, p;
    std::copy(view, view + 16, v.begin());
    std::copy(projection, projection + 16, p.begin());
    m_viewer->render(v, p);
}

void PreviewRenderer::set_layer_range(uint32_t first, uint32_t last)
{
    m_first_layer = first;
    m_last_layer  = last;
    if (!m_loaded)
        return;
    const size_t count = m_viewer->get_layers_count();
    if (count == 0)
        return;
    last  = std::min<uint32_t>(last, uint32_t(count - 1));
    first = std::min(first, last);
    m_viewer->set_layers_view_range(first, last);
    set_move_fraction(m_move_fraction);
}

void PreviewRenderer::set_move_fraction(float fraction)
{
    m_move_fraction = std::clamp(fraction, 0.f, 1.f);
    if (!m_loaded)
        return;
    const Interval& enabled = m_viewer->get_view_enabled_range();
    const size_t last = enabled[0] + size_t(std::lround(m_move_fraction * float(enabled[1] - enabled[0])));
    m_viewer->set_view_visible_range(uint32_t(enabled[0]), uint32_t(std::max(last, enabled[0])));
}

void PreviewRenderer::set_view_type(int type)
{
    if (type >= 0 && type < int(EViewType::COUNT)) {
        m_view_type = type;
        m_viewer->set_view_type(EViewType(type));
        update_info();
    }
}

void PreviewRenderer::set_option_visible(int option, bool visible)
{
    if (option < 0 || option >= int(EOptionType::COUNT))
        return;
    if (option == int(EOptionType::Travels))
        m_travels = visible;
    if (m_viewer->is_option_visible(EOptionType(option)) != visible)
        m_viewer->toggle_option_visibility(EOptionType(option));
}

void PreviewRenderer::set_role_visible(int role, bool visible)
{
    if (role < 0 || role >= int(EGCodeExtrusionRole::COUNT))
        return;
    if (m_viewer->is_extrusion_role_visible(EGCodeExtrusionRole(role)) != visible)
        m_viewer->toggle_extrusion_role_visibility(EGCodeExtrusionRole(role));
}

void PreviewRenderer::set_top_layer_only(bool enabled)
{
    if (m_viewer->is_top_layer_only_view_range() != enabled)
        m_viewer->toggle_top_layer_only_view_range();
}

static std::string color_hex(const Color& c)
{
    char buf[8];
    snprintf(buf, sizeof(buf), "#%02X%02X%02X", c[0], c[1], c[2]);
    return buf;
}

void PreviewRenderer::update_info()
{
    using nlohmann::json;
    json info;
    info["loaded"] = m_loaded;
    json roles = json::array();
    const auto used = m_loaded ? m_viewer->get_extrusion_roles() : std::vector<EGCodeExtrusionRole>{};
    for (int r = 0; r < int(EGCodeExtrusionRole::COUNT); ++r) {
        const auto role = EGCodeExtrusionRole(r);
        roles.push_back({
            {"id", r},
            {"color", color_hex(m_viewer->get_extrusion_role_color(role))},
            {"used", std::find(used.begin(), used.end(), role) != used.end()},
            {"time", m_loaded ? m_viewer->get_extrusion_role_estimated_time(role) : 0.f},
        });
    }
    info["roles"] = roles;
    info["view_type"] = int(m_viewer->get_view_type());
    if (m_loaded) {
        info["layers"] = m_viewer->get_layers_count();
        info["time"]   = m_viewer->get_estimated_time();
        const EViewType vt = m_viewer->get_view_type();
        if (vt != EViewType::FeatureType && vt != EViewType::Tool && vt != EViewType::ColorPrint) {
            const ColorRange& range = m_viewer->get_color_range(vt);
            json colors = json::array();
            for (float v : range.get_values())
                colors.push_back({ {"value", v}, {"color", color_hex(range.get_color_at(v))} });
            info["range"] = colors;
        }
    }
    std::lock_guard<std::mutex> lock(m_info_mutex);
    m_info = info.dump();
}

std::string PreviewRenderer::info_json() const
{
    std::lock_guard<std::mutex> lock(m_info_mutex);
    return m_info;
}

} // namespace andslice

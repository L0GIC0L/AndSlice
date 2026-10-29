#include "GCodePreview.hpp"

#include <algorithm>

#include <nlohmann/json.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/Color.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"

#include <GCodeInputData.hpp>
#include <PathVertex.hpp>
#include <Types.hpp>

namespace andslice {

using namespace libvgcode;

// The conversions below follow PrusaSlicer's src/slic3r/GUI/LibVGCode/LibVGCodeWrapper.cpp
// (AGPL-3.0), which feeds the same viewer in the desktop application.

static Vec3 convert(const Slic3r::Vec3f& v) { return { v.x(), v.y(), v.z() }; }

static Color convert(const std::string& color_str)
{
    Slic3r::ColorRGBA c;
    if (!Slic3r::decode_color(color_str, c))
        return DUMMY_COLOR;
    return { uint8_t(c.r() * 255.f), uint8_t(c.g() * 255.f), uint8_t(c.b() * 255.f) };
}

static EGCodeExtrusionRole convert(Slic3r::GCodeExtrusionRole role)
{
    using R = Slic3r::GCodeExtrusionRole;
    switch (role) {
    case R::Perimeter:                return EGCodeExtrusionRole::Perimeter;
    case R::ExternalPerimeter:        return EGCodeExtrusionRole::ExternalPerimeter;
    case R::OverhangPerimeter:        return EGCodeExtrusionRole::OverhangPerimeter;
    case R::InternalInfill:           return EGCodeExtrusionRole::InternalInfill;
    case R::SolidInfill:              return EGCodeExtrusionRole::SolidInfill;
    case R::TopSolidInfill:           return EGCodeExtrusionRole::TopSolidInfill;
    case R::Ironing:                  return EGCodeExtrusionRole::Ironing;
    case R::BridgeInfill:             return EGCodeExtrusionRole::BridgeInfill;
    case R::GapFill:                  return EGCodeExtrusionRole::GapFill;
    case R::Skirt:                    return EGCodeExtrusionRole::Skirt;
    case R::SupportMaterial:          return EGCodeExtrusionRole::SupportMaterial;
    case R::SupportMaterialInterface: return EGCodeExtrusionRole::SupportMaterialInterface;
    case R::WipeTower:                return EGCodeExtrusionRole::WipeTower;
    case R::Custom:                   return EGCodeExtrusionRole::Custom;
    default:                          return EGCodeExtrusionRole::None;
    }
}

static EMoveType convert(Slic3r::EMoveType type)
{
    using T = Slic3r::EMoveType;
    switch (type) {
    case T::Noop:         return EMoveType::Noop;
    case T::Retract:      return EMoveType::Retract;
    case T::Unretract:    return EMoveType::Unretract;
    case T::Seam:         return EMoveType::Seam;
    case T::Tool_change:  return EMoveType::ToolChange;
    case T::Color_change: return EMoveType::ColorChange;
    case T::Pause_Print:  return EMoveType::PausePrint;
    case T::Custom_GCode: return EMoveType::CustomGCode;
    case T::Travel:       return EMoveType::Travel;
    case T::Wipe:         return EMoveType::Wipe;
    case T::Extrude:      return EMoveType::Extrude;
    case T::Flush:        return EMoveType::Flush;
    default:              return EMoveType::COUNT;
    }
}

std::shared_ptr<GCodeInputData> make_preview(const Slic3r::GCodeProcessorResult& result,
                                             const std::vector<std::string>& tool_colors)
{
    auto ret = std::make_shared<GCodeInputData>();
    for (const std::string& color : tool_colors)
        ret->tools_colors.emplace_back(convert(color));
    ret->color_print_colors = ret->tools_colors;

    const auto& moves = result.moves;
    ret->vertices.reserve(2 * moves.size());
    for (size_t i = 1; i < moves.size(); ++i) {
        const auto& curr = moves[i];
        const auto& prev = moves[i - 1];
        const EMoveType curr_type = convert(curr.type);
        const EOptionType option_type = move_type_to_option(curr_type);

        // Flush extrusions (between FLUSH_START/FLUSH_END tags) are not shown in the preview.
        if (curr_type == EMoveType::Flush)
            continue;

        if (option_type == EOptionType::COUNT || option_type == EOptionType::Travels || option_type == EOptionType::Wipes) {
            if (ret->vertices.empty() || prev.type != curr.type || prev.extrusion_role != curr.extrusion_role) {
                // A 'phantom' vertex at the previous position lets libvgcode detect where a path starts.
                ret->vertices.push_back({ convert(prev.position), curr.height, curr.width, curr.feedrate, prev.actual_feedrate,
                    curr.mm3_per_mm, curr.fan_speed, curr.temperature, convert(curr.extrusion_role), curr_type,
                    uint32_t(curr.gcode_id), uint32_t(curr.layer_id), uint8_t(curr.extruder_id), uint8_t(curr.cp_color_id),
                    { 0.0f, 0.0f } });
            }
        }

        ret->vertices.push_back({ convert(curr.position), curr.height, curr.width, curr.feedrate, curr.actual_feedrate,
            curr.mm3_per_mm, curr.fan_speed, curr.temperature, convert(curr.extrusion_role), curr_type,
            uint32_t(curr.gcode_id), uint32_t(curr.layer_id), uint8_t(curr.extruder_id), uint8_t(curr.cp_color_id),
            curr.time });
    }
    ret->vertices.shrink_to_fit();
    ret->spiral_vase_mode = result.spiral_vase_mode;
    return ret;
}

std::vector<float> preview_layer_zs(const GCodeInputData& data)
{
    std::vector<float> zs;
    for (const PathVertex& v : data.vertices) {
        if (v.type != EMoveType::Extrude)
            continue;
        if (v.layer_id >= zs.size())
            zs.resize(v.layer_id + 1, 0.f);
        zs[v.layer_id] = std::max(zs[v.layer_id], v.position[2]);
    }
    // Layers without extrusions (rare) inherit the Z below them.
    for (size_t i = 1; i < zs.size(); ++i)
        if (zs[i] == 0.f)
            zs[i] = zs[i - 1];
    return zs;
}

} // namespace andslice

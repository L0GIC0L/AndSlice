// G-code preview built on PrusaSlicer's libvgcode viewer.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace Slic3r {
struct GCodeProcessorResult;
}

namespace libvgcode {
struct GCodeInputData;
}

namespace andslice {

// Converts the G-code processor output into libvgcode input (any thread).
std::shared_ptr<libvgcode::GCodeInputData> make_preview(const Slic3r::GCodeProcessorResult& result,
                                                        const std::vector<std::string>& tool_colors);

// Top Z of every layer in the preview, indexed by layer id.
std::vector<float> preview_layer_zs(const libvgcode::GCodeInputData& data);

} // namespace andslice

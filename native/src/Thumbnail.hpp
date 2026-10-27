// CPU renderer for the G-code thumbnails shown on the printer's screen.
#pragma once

#include <vector>

#include "Engine.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"

namespace andslice {

// Renders the plate objects from an isometric viewpoint at every requested size.
// Pixels are RGBA, bottom row first, as PrusaSlicer's thumbnail writer expects.
Slic3r::ThumbnailsList render_thumbnails(const std::vector<PlateObject>& scene, const BedShape& bed,
                                         const Slic3r::ThumbnailsParams& params);

} // namespace andslice

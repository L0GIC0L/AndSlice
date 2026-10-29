// Mesh repair with CGAL's polygon mesh processing, for models with holes or broken facets.
#pragma once

#include <cstddef>

#include <libslic3r/TriangleMesh.hpp>

namespace andslice {

struct MeshRepairResult {
    indexed_triangle_set its;
    int holes_filled = 0;
    int holes_left   = 0; // too large to fill
};

// Repairs a triangle mesh: duplicate points and degenerate or repeated faces are removed, faces are
// oriented consistently, borders that meet are stitched, holes of up to `max_hole_edges` edges are
// filled, and a closed mesh is turned to face outward.
MeshRepairResult repair_mesh(const indexed_triangle_set& its, std::size_t max_hole_edges = 5000);

} // namespace andslice

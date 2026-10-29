// Replacement for PrusaSlicer's libslic3r/OpenVDBUtils.cpp on Android.
//
// OpenVDB is only used by the SLA (resin) pipeline for hollowing and CSG
// voxelization. AndSlice slices for FDM printers only, so OpenVDB is not built
// and these entry points report that the feature is unavailable.
#include "libslic3r/OpenVDBUtils.hpp"

#include "libslic3r/Exception.hpp"

namespace Slic3r {

struct VoxelGrid {};

void VoxelGridDeleter::operator()(VoxelGrid *ptr) { delete ptr; }

template<class... Args> VoxelGridPtr make_voxelgrid(Args &&...)
{
    return VoxelGridPtr{new VoxelGrid{}};
}

template VoxelGridPtr make_voxelgrid<>();

[[noreturn]] static void unsupported()
{
    throw RuntimeError("Voxel operations (SLA hollowing) are not available in this build.");
}

void reset_accessor(const VoxelGrid &) {}
double get_distance_raw(const Vec3f &, const VoxelGrid &) { unsupported(); }
float get_voxel_scale(const VoxelGrid &) { unsupported(); }
VoxelGridPtr clone(const VoxelGrid &) { unsupported(); }
VoxelGridPtr mesh_to_grid(const indexed_triangle_set &, const MeshToGridParams &) { unsupported(); }
indexed_triangle_set grid_to_mesh(const VoxelGrid &, double, double, bool) { unsupported(); }
VoxelGridPtr dilate_grid(const VoxelGrid &, float, float) { unsupported(); }
VoxelGridPtr redistance_grid(const VoxelGrid &, float) { unsupported(); }
VoxelGridPtr redistance_grid(const VoxelGrid &, float, float, float) { unsupported(); }
void rescale_grid(VoxelGrid &, float) { unsupported(); }
void grid_union(VoxelGrid &, VoxelGrid &) { unsupported(); }
void grid_difference(VoxelGrid &, VoxelGrid &) { unsupported(); }
void grid_intersection(VoxelGrid &, VoxelGrid &) { unsupported(); }
bool is_grid_empty(const VoxelGrid &) { return true; }

} // namespace Slic3r

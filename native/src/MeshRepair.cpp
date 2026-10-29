#include "MeshRepair.hpp"

#include <array>
#include <iterator>
#include <vector>

#include <CGAL/Exact_predicates_inexact_constructions_kernel.h>
#include <CGAL/Surface_mesh.h>
#include <CGAL/boost/graph/helpers.h>
#include <CGAL/Polygon_mesh_processing/border.h>
#include <CGAL/Polygon_mesh_processing/orient_polygon_soup.h>
#include <CGAL/Polygon_mesh_processing/orientation.h>
#include <CGAL/Polygon_mesh_processing/polygon_soup_to_polygon_mesh.h>
#include <CGAL/Polygon_mesh_processing/repair_polygon_soup.h>
#include <CGAL/Polygon_mesh_processing/stitch_borders.h>
#include <CGAL/Polygon_mesh_processing/triangulate_hole.h>

namespace andslice {

namespace PMP = CGAL::Polygon_mesh_processing;
using Kernel = CGAL::Exact_predicates_inexact_constructions_kernel;
using Mesh   = CGAL::Surface_mesh<Kernel::Point_3>;

MeshRepairResult repair_mesh(const indexed_triangle_set& in, std::size_t max_hole_edges)
{
    // As a polygon soup first, so faces that do not fit together still make it into the mesh.
    std::vector<Kernel::Point_3> points;
    points.reserve(in.vertices.size());
    for (const auto& v : in.vertices)
        points.emplace_back(v.x(), v.y(), v.z());
    std::vector<std::vector<std::size_t>> faces;
    faces.reserve(in.indices.size());
    for (const auto& f : in.indices)
        faces.push_back({ std::size_t(f[0]), std::size_t(f[1]), std::size_t(f[2]) });

    PMP::repair_polygon_soup(points, faces);
    PMP::orient_polygon_soup(points, faces);
    Mesh mesh;
    PMP::polygon_soup_to_polygon_mesh(points, faces, mesh);
    PMP::stitch_borders(mesh);

    MeshRepairResult out;
    std::vector<Mesh::Halfedge_index> holes;
    PMP::extract_boundary_cycles(mesh, std::back_inserter(holes));
    for (Mesh::Halfedge_index h : holes) {
        std::size_t edges = 0;
        for (Mesh::Halfedge_index e : CGAL::halfedges_around_face(h, mesh)) {
            (void)e;
            ++edges;
        }
        if (edges > max_hole_edges) {
            ++out.holes_left;
            continue;
        }
        std::vector<Mesh::Face_index> patch;
        PMP::triangulate_hole(mesh, h, CGAL::parameters::face_output_iterator(std::back_inserter(patch)));
        if (patch.empty())
            ++out.holes_left;
        else
            ++out.holes_filled;
    }
    if (CGAL::is_closed(mesh))
        PMP::orient_to_bound_a_volume(mesh);

    mesh.collect_garbage();
    out.its.vertices.reserve(mesh.number_of_vertices());
    for (Mesh::Vertex_index v : mesh.vertices()) {
        const Kernel::Point_3& p = mesh.point(v);
        out.its.vertices.emplace_back(float(p.x()), float(p.y()), float(p.z()));
    }
    out.its.indices.reserve(mesh.number_of_faces());
    for (Mesh::Face_index f : mesh.faces()) {
        std::array<int, 3> idx{};
        int                n = 0;
        for (Mesh::Vertex_index v : CGAL::vertices_around_face(mesh.halfedge(f), mesh)) {
            if (n < 3)
                idx[n] = int(v.idx());
            ++n;
        }
        if (n == 3)
            out.its.indices.emplace_back(idx[0], idx[1], idx[2]);
    }
    return out;
}

} // namespace andslice

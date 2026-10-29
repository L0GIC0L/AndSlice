#include "Thumbnail.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

#include <Eigen/Geometry>

namespace andslice {

using Vec3 = Eigen::Vector3f;

namespace {

struct Canvas {
    int w, h;
    std::vector<float>         depth;
    std::vector<unsigned char> rgba;
    Canvas(int w, int h) : w(w), h(h), depth(size_t(w) * h, std::numeric_limits<float>::max()), rgba(size_t(w) * h * 4, 0) {}
};

// Fills one screen-space triangle with a flat color, using a z-buffer.
void raster(Canvas& c, const Vec3& a, const Vec3& b, const Vec3& d, const unsigned char color[3])
{
    const float area = (b.x() - a.x()) * (d.y() - a.y()) - (b.y() - a.y()) * (d.x() - a.x());
    if (std::abs(area) < 1e-12f)
        return;
    const int x0 = std::max(0, int(std::floor(std::min({ a.x(), b.x(), d.x() }))));
    const int x1 = std::min(c.w - 1, int(std::ceil(std::max({ a.x(), b.x(), d.x() }))));
    const int y0 = std::max(0, int(std::floor(std::min({ a.y(), b.y(), d.y() }))));
    const int y1 = std::min(c.h - 1, int(std::ceil(std::max({ a.y(), b.y(), d.y() }))));
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float px = x + 0.5f, py = y + 0.5f;
            const float w0 = ((b.x() - px) * (d.y() - py) - (b.y() - py) * (d.x() - px)) / area;
            const float w1 = ((d.x() - px) * (a.y() - py) - (d.y() - py) * (a.x() - px)) / area;
            const float w2 = 1.f - w0 - w1;
            if (w0 < 0.f || w1 < 0.f || w2 < 0.f)
                continue;
            const float z = w0 * a.z() + w1 * b.z() + w2 * d.z();
            const size_t idx = size_t(y) * c.w + x;
            if (z >= c.depth[idx])
                continue;
            c.depth[idx] = z;
            c.rgba[idx * 4 + 0] = color[0];
            c.rgba[idx * 4 + 1] = color[1];
            c.rgba[idx * 4 + 2] = color[2];
            c.rgba[idx * 4 + 3] = 255;
        }
    }
}

} // namespace

Slic3r::ThumbnailsList render_thumbnails(const std::vector<PlateObject>& scene, const BedShape&,
                                         const Slic3r::ThumbnailsParams& params)
{
    Slic3r::ThumbnailsList out;

    // View from the front-left, above the bed, like PrusaSlicer's "iso" camera.
    const Vec3 view_dir = Vec3(1.f, 1.f, -1.f).normalized(); // camera looks along this direction
    const Vec3 right    = view_dir.cross(Vec3::UnitZ()).normalized();
    const Vec3 up       = right.cross(view_dir).normalized();
    const Vec3 light    = Vec3(-0.4f, -0.6f, 1.f).normalized();

    // Transform every object once into world space.
    struct Tri { Vec3 v[3]; };
    std::vector<Tri> tris;
    Eigen::AlignedBox3f box;
    for (const PlateObject& obj : scene) {
        if (!obj.mesh || obj.outside)
            continue;
        const Eigen::Map<const Eigen::Matrix4f> m(obj.transform.data());
        const auto& P = obj.mesh->positions;
        const auto& I = obj.mesh->indices;
        tris.reserve(tris.size() + I.size() / 3);
        for (size_t i = 0; i + 2 < I.size(); i += 3) {
            Tri t;
            for (int k = 0; k < 3; ++k) {
                const uint32_t vi = I[i + k];
                const Eigen::Vector4f p = m * Eigen::Vector4f(P[3 * vi], P[3 * vi + 1], P[3 * vi + 2], 1.f);
                t.v[k] = p.head<3>();
                box.extend(t.v[k]);
            }
            tris.push_back(t);
        }
    }
    if (tris.empty())
        return out;

    // Screen-space extent of the scene in camera coordinates.
    Eigen::AlignedBox2f extent;
    for (int corner = 0; corner < 8; ++corner) {
        const Vec3 p = box.corner(Eigen::AlignedBox3f::CornerType(corner));
        extent.extend(Eigen::Vector2f(p.dot(right), p.dot(up)));
    }

    for (const Slic3r::Vec2d& size : params.sizes) {
        const int w = std::max(1, int(size.x())), h = std::max(1, int(size.y()));
        Canvas canvas(w, h);
        const float margin = 0.9f;
        const float scale  = margin * std::min(w / std::max(extent.sizes().x(), 1e-3f),
                                               h / std::max(extent.sizes().y(), 1e-3f));
        const Eigen::Vector2f center = extent.center();
        auto project = [&](const Vec3& p) {
            return Vec3((p.dot(right) - center.x()) * scale + w * 0.5f,
                        (p.dot(up) - center.y()) * scale + h * 0.5f,
                        p.dot(view_dir));
        };

        for (const Tri& t : tris) {
            Vec3 n = (t.v[1] - t.v[0]).cross(t.v[2] - t.v[0]);
            if (n.squaredNorm() < 1e-20f)
                continue;
            n.normalize();
            if (n.dot(view_dir) > 0.f)
                continue; // back face
            const float shade = 0.2f + 0.5f * std::max(0.f, n.dot(light)) + 0.3f * std::max(0.f, -n.dot(view_dir));
            // Anycubic blue-ish gray reads well on the printer screen.
            const unsigned char color[3] = {
                (unsigned char)std::min(255.f, 90.f * shade + 40.f),
                (unsigned char)std::min(255.f, 170.f * shade + 40.f),
                (unsigned char)std::min(255.f, 230.f * shade + 25.f) };
            raster(canvas, project(t.v[0]), project(t.v[1]), project(t.v[2]), color);
        }

        Slic3r::ThumbnailData data;
        data.set(unsigned(w), unsigned(h));
        // The canvas origin is the bottom-left corner, which matches the bottom-up rows of ThumbnailData.
        data.pixels = std::move(canvas.rgba);
        if (!params.transparent_background)
            for (size_t i = 3; i < data.pixels.size(); i += 4)
                data.pixels[i] = 255;
        out.push_back(std::move(data));
    }
    return out;
}

} // namespace andslice

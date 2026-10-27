#include "SceneRenderer.hpp"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include <GCodeInputData.hpp>

#define LOG_TAG "AndSlice"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace andslice {

namespace {

const char* MESH_VS = R"(#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 u_viewproj;
uniform mat4 u_model;
out vec3 v_world;
void main() {
    vec4 w = u_model * vec4(a_pos, 1.0);
    v_world = w.xyz;
    gl_Position = u_viewproj * w;
})";

// Flat shading from screen-space derivatives: no per-vertex normals needed.
const char* MESH_FS = R"(#version 300 es
precision highp float;
in vec3 v_world;
uniform vec4 u_color;
out vec4 o_color;
void main() {
    vec3 n = normalize(cross(dFdx(v_world), dFdy(v_world)));
    vec3 key  = normalize(vec3(-0.45, -0.65, 1.0));
    vec3 fill = normalize(vec3(0.7, 0.4, 0.3));
    float light = 0.32 + 0.55 * abs(dot(n, key)) + 0.18 * max(dot(n, fill), 0.0);
    o_color = vec4(u_color.rgb * light, u_color.a);
})";

const char* LINE_VS = R"(#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 u_viewproj;
void main() { gl_Position = u_viewproj * vec4(a_pos, 1.0); })";

const char* LINE_FS = R"(#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 o_color;
void main() { o_color = u_color; })";

GLuint compile(GLenum type, const char* src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
    }
    return s;
}

GLuint link(const char* vs, const char* fs)
{
    GLuint p = glCreateProgram();
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("program link failed: %s", log);
    }
    return p;
}

// Column-major 4x4 product: out = a * b.
void mul4(const float a[16], const float b[16], float out[16])
{
    for (int c = 0; c < 4; ++c)
        for (int r = 0; r < 4; ++r) {
            float s = 0.f;
            for (int k = 0; k < 4; ++k)
                s += a[k * 4 + r] * b[c * 4 + k];
            out[c * 4 + r] = s;
        }
}

} // namespace

SceneRenderer& SceneRenderer::get()
{
    static SceneRenderer renderer;
    return renderer;
}

std::string SceneRenderer::on_surface_created()
{
    // A new EGL context means every previous GL object is gone.
    m_meshes.clear();
    m_bed_vao = m_bed_vbo = 0;
    m_plate_revision = 0;
    m_preview_revision = 0;
    m_preview.release();

    m_mesh_program = link(MESH_VS, MESH_FS);
    m_line_program = link(LINE_VS, LINE_FS);

    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    m_preview_error = m_preview.init(version ? version : "");
    if (!m_preview_error.empty())
        LOGE("G-code preview unavailable: %s", m_preview_error.c_str());
    return m_preview_error;
}

void SceneRenderer::on_surface_changed(int width, int height)
{
    m_width  = std::max(width, 1);
    m_height = std::max(height, 1);
    glViewport(0, 0, m_width, m_height);
}

void SceneRenderer::sync_bed()
{
    const BedShape bed = Engine::get().bed();
    std::vector<float> v;
    if (bed.outline.size() >= 3) {
        float minx = 1e9f, miny = 1e9f, maxx = -1e9f, maxy = -1e9f;
        for (const auto& p : bed.outline) {
            minx = std::min(minx, p[0]); maxx = std::max(maxx, p[0]);
            miny = std::min(miny, p[1]); maxy = std::max(maxy, p[1]);
        }
        // Plate surface as a triangle fan, slightly below z=0 to avoid fighting the first layer.
        m_bed_fill_first = 0;
        const float z = -0.02f;
        const float cx = 0.5f * (minx + maxx), cy = 0.5f * (miny + maxy);
        v.insert(v.end(), { cx, cy, z });
        for (size_t i = 0; i <= bed.outline.size(); ++i) {
            const auto& p = bed.outline[i % bed.outline.size()];
            v.insert(v.end(), { p[0], p[1], z });
        }
        m_bed_fill_count = int(bed.outline.size()) + 2;

        // 10 mm grid.
        m_bed_grid_first = int(v.size() / 3);
        for (float x = std::ceil(minx / 10.f) * 10.f; x <= maxx; x += 10.f)
            v.insert(v.end(), { x, miny, 0.f, x, maxy, 0.f });
        for (float y = std::ceil(miny / 10.f) * 10.f; y <= maxy; y += 10.f)
            v.insert(v.end(), { minx, y, 0.f, maxx, y, 0.f });
        m_bed_grid_count = int(v.size() / 3) - m_bed_grid_first;

        // Build volume edges.
        m_bed_edge_first = int(v.size() / 3);
        const float h = bed.max_height;
        const size_t n = bed.outline.size();
        for (size_t i = 0; i < n; ++i) {
            const auto& a = bed.outline[i];
            const auto& b = bed.outline[(i + 1) % n];
            v.insert(v.end(), { a[0], a[1], 0.f, b[0], b[1], 0.f });
            v.insert(v.end(), { a[0], a[1], h, b[0], b[1], h });
            v.insert(v.end(), { a[0], a[1], 0.f, a[0], a[1], h });
        }
        m_bed_edge_count = int(v.size() / 3) - m_bed_edge_first;
    } else {
        m_bed_fill_count = m_bed_grid_count = m_bed_edge_count = 0;
    }

    if (m_bed_vao == 0) {
        glGenVertexArrays(1, &m_bed_vao);
        glGenBuffers(1, &m_bed_vbo);
    }
    glBindVertexArray(m_bed_vao);
    glBindBuffer(GL_ARRAY_BUFFER, m_bed_vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * sizeof(float)), v.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);
}

void SceneRenderer::sync_plate()
{
    Engine& engine = Engine::get();
    const uint64_t rev = engine.plate_revision();
    if (rev == m_plate_revision)
        return;
    m_plate_revision = rev;
    m_plate = engine.plate_snapshot();
    sync_bed();

    // Drop meshes of removed objects, upload new ones.
    for (auto it = m_meshes.begin(); it != m_meshes.end();) {
        const bool alive = std::any_of(m_plate.begin(), m_plate.end(),
                                       [&](const PlateObject& o) { return o.id == it->first; });
        if (alive) { ++it; continue; }
        glDeleteVertexArrays(1, &it->second.vao);
        glDeleteBuffers(1, &it->second.vbo);
        glDeleteBuffers(1, &it->second.ibo);
        it = m_meshes.erase(it);
    }
    for (const PlateObject& o : m_plate) {
        GpuMesh& gm = m_meshes[o.id];
        if (gm.vao != 0 && gm.source.lock() == o.mesh)
            continue;
        if (gm.vao == 0) {
            glGenVertexArrays(1, &gm.vao);
            glGenBuffers(1, &gm.vbo);
            glGenBuffers(1, &gm.ibo);
        }
        glBindVertexArray(gm.vao);
        glBindBuffer(GL_ARRAY_BUFFER, gm.vbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(o.mesh->positions.size() * sizeof(float)), o.mesh->positions.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gm.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(o.mesh->indices.size() * sizeof(uint32_t)), o.mesh->indices.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glBindVertexArray(0);
        gm.index_count = int(o.mesh->indices.size());
        gm.source = o.mesh;
    }
}

void SceneRenderer::sync_preview()
{
    if (!m_preview.ready())
        return;
    uint64_t rev = 0;
    std::shared_ptr<libvgcode::GCodeInputData> data = Engine::get().preview(rev);
    if (rev == m_preview_revision)
        return;
    m_preview_revision = rev;
    if (data)
        m_preview.load(*data);
    else
        m_preview.clear();
}

void SceneRenderer::draw_bed(const float viewproj[16])
{
    if (m_bed_vao == 0)
        return;
    glUseProgram(m_line_program);
    glUniformMatrix4fv(glGetUniformLocation(m_line_program, "u_viewproj"), 1, GL_FALSE, viewproj);
    const GLint color = glGetUniformLocation(m_line_program, "u_color");
    glBindVertexArray(m_bed_vao);

    glDepthMask(GL_FALSE);
    glUniform4f(color, 0.22f, 0.23f, 0.26f, 1.f);
    glDrawArrays(GL_TRIANGLE_FAN, m_bed_fill_first, m_bed_fill_count);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glUniform4f(color, 0.55f, 0.57f, 0.62f, 0.35f);
    glDrawArrays(GL_LINES, m_bed_grid_first, m_bed_grid_count);
    glUniform4f(color, 0.75f, 0.77f, 0.82f, 0.6f);
    glDrawArrays(GL_LINES, m_bed_edge_first, m_bed_edge_count);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glBindVertexArray(0);
}

void SceneRenderer::draw_objects(const float viewproj[16], bool dimmed)
{
    glUseProgram(m_mesh_program);
    glUniformMatrix4fv(glGetUniformLocation(m_mesh_program, "u_viewproj"), 1, GL_FALSE, viewproj);
    const GLint model = glGetUniformLocation(m_mesh_program, "u_model");
    const GLint color = glGetUniformLocation(m_mesh_program, "u_color");
    if (dimmed) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(GL_FALSE);
    }
    for (const PlateObject& o : m_plate) {
        auto it = m_meshes.find(o.id);
        if (it == m_meshes.end() || it->second.index_count == 0)
            continue;
        glUniformMatrix4fv(model, 1, GL_FALSE, o.transform.data());
        // PrusaSlicer's plater colors: yellow objects, orange selection, blue when outside the bed.
        if (dimmed)
            glUniform4f(color, 0.8f, 0.8f, 0.8f, 0.12f);
        else if (o.outside)
            glUniform4f(color, 0.35f, 0.55f, 0.95f, 1.f);
        else if (o.selected)
            glUniform4f(color, 1.0f, 0.55f, 0.15f, 1.f);
        else
            glUniform4f(color, 1.0f, 0.86f, 0.3f, 1.f);
        glBindVertexArray(it->second.vao);
        glDrawElements(GL_TRIANGLES, it->second.index_count, GL_UNSIGNED_INT, nullptr);
    }
    glBindVertexArray(0);
    if (dimmed) {
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
    }
}

void SceneRenderer::draw(const float view[16], const float projection[16], int mode)
{
    sync_plate();
    sync_preview();

    float viewproj[16];
    mul4(projection, view, viewproj);

    glViewport(0, 0, m_width, m_height);
    glClearColor(0.11f, 0.12f, 0.14f, 1.f);
    glClearDepthf(1.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDisable(GL_CULL_FACE);

    draw_bed(viewproj);
    if (mode == ModePreview && m_preview.has_data()) {
        m_preview.render(view, projection);
        // libvgcode leaves its own program and buffers bound.
        glUseProgram(0);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
        draw_objects(viewproj, true);
    } else {
        draw_objects(viewproj, false);
    }
}

} // namespace andslice

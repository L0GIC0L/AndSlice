// JNI entry points for io.github.l0gic0l.andslice.engine.NativeEngine.
#include <jni.h>

#include <algorithm>
#include <string>
#include <utility>
#include <vector>

#include "Engine.hpp"
#include "SceneRenderer.hpp"

using andslice::Engine;
using andslice::SceneRenderer;

namespace {

JavaVM* g_vm = nullptr;

std::string to_string(JNIEnv* env, jstring s)
{
    if (s == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray array)
{
    std::vector<std::string> out;
    const jsize n = array ? env->GetArrayLength(array) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(to_string(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

void throw_java(JNIEnv* env, const char* message)
{
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, message);
}

// Runs fn and turns C++ exceptions into Java RuntimeExceptions.
template<class Fn, class R = decltype(std::declval<Fn>()())>
R guarded(JNIEnv* env, Fn&& fn, R fallback = R())
{
    try {
        return fn();
    } catch (const std::exception& ex) {
        throw_java(env, ex.what());
    } catch (...) {
        throw_java(env, "Unknown native error");
    }
    return fallback;
}

template<class Fn>
void guarded_void(JNIEnv* env, Fn&& fn)
{
    guarded(env, [&]() { fn(); return 0; }, 0);
}

// JNIEnv for the current thread, attaching it as a daemon when it is a native
// worker thread (progress callbacks may arrive from TBB threads).
JNIEnv* current_env()
{
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
        g_vm->AttachCurrentThreadAsDaemon(&env, nullptr);
    return env;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}

#define FN(name) Java_io_github_l0gic0l_andslice_engine_NativeEngine_##name

// ------------------------------------------------------------------- profiles

JNIEXPORT void JNICALL FN(init)(JNIEnv* env, jobject, jstring data_dir, jstring resources_dir, jobjectArray bundles)
{
    guarded_void(env, [&] {
        Engine::get().init(to_string(env, data_dir), to_string(env, resources_dir), to_strings(env, bundles));
    });
}

JNIEXPORT void JNICALL FN(loadProfiles)(JNIEnv* env, jobject, jobjectArray bundles)
{
    guarded_void(env, [&] { Engine::get().load_profiles(to_strings(env, bundles)); });
}

JNIEXPORT jstring JNICALL FN(printerForModel)(JNIEnv* env, jobject, jstring model, jstring variant)
{
    return guarded(env, [&] {
        return to_jstring(env, Engine::get().printer_for_model(to_string(env, model), to_string(env, variant)));
    }, (jstring) nullptr);
}

JNIEXPORT jstring JNICALL FN(savePreset)(JNIEnv* env, jobject, jstring type, jstring name, jstring printer, jstring print,
                                        jobjectArray filaments, jobjectArray keys, jobjectArray values)
{
    return guarded(env, [&] {
        const std::vector<std::string> k = to_strings(env, keys), v = to_strings(env, values);
        std::vector<std::pair<std::string, std::string>> overrides;
        for (size_t i = 0; i < std::min(k.size(), v.size()); ++i)
            overrides.emplace_back(k[i], v[i]);
        return to_jstring(env, Engine::get().save_preset(to_string(env, type), to_string(env, name), to_string(env, printer),
                                                         to_string(env, print), to_strings(env, filaments), overrides));
    }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(deletePreset)(JNIEnv* env, jobject, jstring type, jstring name)
{
    guarded_void(env, [&] { Engine::get().delete_preset(to_string(env, type), to_string(env, name)); });
}

JNIEXPORT jstring JNICALL FN(presetsJson)(JNIEnv* env, jobject, jstring printer)
{
    return guarded(env, [&] { return to_jstring(env, Engine::get().presets_json(to_string(env, printer))); }, (jstring) nullptr);
}

JNIEXPORT jstring JNICALL FN(configJson)(JNIEnv* env, jobject, jstring printer, jstring print, jobjectArray filaments)
{
    return guarded(env, [&] {
        return to_jstring(env, Engine::get().config_json(to_string(env, printer), to_string(env, print), to_strings(env, filaments)));
    }, (jstring) nullptr);
}

JNIEXPORT jstring JNICALL FN(optionDefsJson)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return to_jstring(env, Engine::get().option_defs_json()); }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(setPrinter)(JNIEnv* env, jobject, jstring printer)
{
    guarded_void(env, [&] { Engine::get().set_printer(to_string(env, printer)); });
}

// ------------------------------------------------------------------- plate

JNIEXPORT void JNICALL FN(setArrangeDistance)(JNIEnv* env, jobject, jfloat mm)
{
    guarded_void(env, [&] { Engine::get().set_arrange_distance(mm); });
}

JNIEXPORT jstring JNICALL FN(addModel)(JNIEnv* env, jobject, jstring path, jstring name)
{
    return guarded(env, [&] { return to_jstring(env, Engine::get().add_model(to_string(env, path), to_string(env, name))); }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(removeObject)(JNIEnv* env, jobject, jlong id)
{
    guarded_void(env, [&] { Engine::get().remove_object(uint64_t(id)); });
}

JNIEXPORT void JNICALL FN(clearPlate)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().clear_plate(); });
}

JNIEXPORT jlong JNICALL FN(duplicateObject)(JNIEnv* env, jobject, jlong id)
{
    return guarded(env, [&] { return jlong(Engine::get().duplicate_object(uint64_t(id))); }, jlong(0));
}

JNIEXPORT void JNICALL FN(setTransform)(JNIEnv* env, jobject, jlong id, jdoubleArray t)
{
    guarded_void(env, [&] {
        if (env->GetArrayLength(t) < 8)
            throw std::invalid_argument("transform needs 8 values");
        double values[8];
        env->GetDoubleArrayRegion(t, 0, 8, values);
        Engine::get().set_transform(uint64_t(id), values);
    });
}

static void read_ray(JNIEnv* env, jfloatArray origin, jfloatArray dir, float o[3], float d[3])
{
    if (env->GetArrayLength(origin) < 3 || env->GetArrayLength(dir) < 3)
        throw std::invalid_argument("ray needs 3 components");
    env->GetFloatArrayRegion(origin, 0, 3, o);
    env->GetFloatArrayRegion(dir, 0, 3, d);
}

JNIEXPORT jboolean JNICALL FN(layOnFace)(JNIEnv* env, jobject, jlong id, jfloatArray origin, jfloatArray dir)
{
    return guarded(env, [&] {
        float o[3], d[3];
        read_ray(env, origin, dir, o, d);
        return jboolean(Engine::get().lay_on_face(uint64_t(id), o, d));
    }, jboolean(false));
}

JNIEXPORT jlong JNICALL FN(pick)(JNIEnv* env, jobject, jfloatArray origin, jfloatArray dir)
{
    return guarded(env, [&] {
        float o[3], d[3];
        read_ray(env, origin, dir, o, d);
        return jlong(Engine::get().pick(o, d));
    }, jlong(-1));
}

JNIEXPORT void JNICALL FN(arrange)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().arrange(); });
}

JNIEXPORT void JNICALL FN(centerObject)(JNIEnv* env, jobject, jlong id)
{
    guarded_void(env, [&] { Engine::get().center_object(uint64_t(id)); });
}

JNIEXPORT jstring JNICALL FN(objectsJson)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return to_jstring(env, Engine::get().objects_json()); }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(select)(JNIEnv* env, jobject, jlong id)
{
    guarded_void(env, [&] { Engine::get().select(int64_t(id)); });
}

JNIEXPORT void JNICALL FN(setExtruder)(JNIEnv* env, jobject, jlong id, jint part, jint extruder)
{
    guarded_void(env, [&] { Engine::get().set_extruder(uint64_t(id), part, extruder); });
}

// values = {x, y, width, depth, brim, height}
JNIEXPORT void JNICALL FN(setWipeTower)(JNIEnv* env, jobject, jboolean visible, jfloatArray values)
{
    guarded_void(env, [&] {
        if (env->GetArrayLength(values) < 6)
            throw std::invalid_argument("wipe tower needs 6 values");
        float v[6];
        env->GetFloatArrayRegion(values, 0, 6, v);
        andslice::WipeTowerShape shape;
        shape.visible = visible;
        shape.x = v[0];
        shape.y = v[1];
        shape.width = v[2];
        shape.depth = v[3];
        shape.brim = v[4];
        shape.height = v[5];
        Engine::get().set_wipe_tower(shape);
    });
}

// {x, y} of the wipe tower, which arranging may have moved.
JNIEXPORT jfloatArray JNICALL FN(wipeTowerPosition)(JNIEnv* env, jobject)
{
    return guarded(env, [&] {
        const andslice::WipeTowerShape shape = Engine::get().wipe_tower();
        const float v[2] = { shape.x, shape.y };
        jfloatArray out = env->NewFloatArray(2);
        env->SetFloatArrayRegion(out, 0, 2, v);
        return out;
    }, (jfloatArray) nullptr);
}

// ------------------------------------------------------------------- painting

JNIEXPORT jboolean JNICALL FN(paintBegin)(JNIEnv* env, jobject, jlong id, jint mode)
{
    return guarded(env, [&] { return jboolean(Engine::get().paint_begin(uint64_t(id), mode)); }, jboolean(false));
}

JNIEXPORT jboolean JNICALL FN(paintAt)(JNIEnv* env, jobject, jfloatArray camera, jfloatArray dir, jfloat radius,
                                      jint tool, jint state, jboolean continue_stroke)
{
    return guarded(env, [&] {
        float c[3], d[3];
        env->GetFloatArrayRegion(camera, 0, 3, c);
        env->GetFloatArrayRegion(dir, 0, 3, d);
        return jboolean(Engine::get().paint_at(c, d, radius, tool, state, continue_stroke));
    }, jboolean(false));
}

JNIEXPORT void JNICALL FN(paintCommit)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().paint_commit(); });
}

JNIEXPORT void JNICALL FN(paintCancelStroke)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().paint_cancel_stroke(); });
}

JNIEXPORT jboolean JNICALL FN(paintUndo)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return jboolean(Engine::get().paint_undo()); }, jboolean(false));
}

JNIEXPORT void JNICALL FN(paintClear)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().paint_clear(); });
}

JNIEXPORT void JNICALL FN(paintEnd)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().paint_end(); });
}

JNIEXPORT jstring JNICALL FN(paintStateJson)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return to_jstring(env, Engine::get().paint_state_json()); }, (jstring) nullptr);
}

// ------------------------------------------------------------------- slicing

JNIEXPORT jstring JNICALL FN(slice)(JNIEnv* env, jobject, jstring printer, jstring print, jobjectArray filaments,
                                   jobjectArray keys, jobjectArray values, jstring output, jobject listener)
{
    return guarded(env, [&] {
        andslice::SliceRequest req;
        req.printer     = to_string(env, printer);
        req.print       = to_string(env, print);
        req.filaments   = to_strings(env, filaments);
        req.output_path = to_string(env, output);
        const jsize n = env->GetArrayLength(keys);
        for (jsize i = 0; i < n; ++i) {
            auto k = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
            auto v = static_cast<jstring>(env->GetObjectArrayElement(values, i));
            req.overrides.emplace_back(to_string(env, k), to_string(env, v));
            env->DeleteLocalRef(k);
            env->DeleteLocalRef(v);
        }

        jobject global_listener = listener ? env->NewGlobalRef(listener) : nullptr;
        jmethodID on_progress = nullptr;
        if (global_listener) {
            jclass cls = env->GetObjectClass(global_listener);
            on_progress = env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;)V");
        }
        const std::string result = Engine::get().slice(req, [&](int percent, const std::string& message) {
            if (!global_listener || !on_progress)
                return;
            JNIEnv* e = current_env();
            jstring msg = e->NewStringUTF(message.c_str());
            e->CallVoidMethod(global_listener, on_progress, jint(percent), msg);
            if (e->ExceptionCheck())
                e->ExceptionClear();
            e->DeleteLocalRef(msg);
        });
        if (global_listener)
            env->DeleteGlobalRef(global_listener);
        return to_jstring(env, result);
    }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(cancelSlice)(JNIEnv*, jobject)
{
    Engine::get().cancel_slice();
}

JNIEXPORT void JNICALL FN(clearPreview)(JNIEnv* env, jobject)
{
    guarded_void(env, [&] { Engine::get().clear_preview(); });
}

// ------------------------------------------------------------------- renderer (GL thread)

JNIEXPORT jstring JNICALL FN(rendererSurfaceCreated)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return to_jstring(env, SceneRenderer::get().on_surface_created()); }, (jstring) nullptr);
}

JNIEXPORT void JNICALL FN(rendererSurfaceChanged)(JNIEnv* env, jobject, jint width, jint height)
{
    guarded_void(env, [&] { SceneRenderer::get().on_surface_changed(width, height); });
}

JNIEXPORT void JNICALL FN(rendererDraw)(JNIEnv* env, jobject, jfloatArray view, jfloatArray projection, jint mode)
{
    guarded_void(env, [&] {
        float v[16], p[16];
        env->GetFloatArrayRegion(view, 0, 16, v);
        env->GetFloatArrayRegion(projection, 0, 16, p);
        SceneRenderer::get().draw(v, p, mode);
    });
}

JNIEXPORT void JNICALL FN(rendererSetPalette)(JNIEnv* env, jobject, jfloatArray colors)
{
    guarded_void(env, [&] {
        constexpr int n = SceneRenderer::PaletteSize * 4;
        if (env->GetArrayLength(colors) < n)
            throw std::invalid_argument("palette needs " + std::to_string(n) + " values");
        float values[n];
        env->GetFloatArrayRegion(colors, 0, n, values);
        SceneRenderer::get().set_palette(values);
    });
}

// RGBA per extruder; an empty array draws objects in the theme color instead.
JNIEXPORT void JNICALL FN(rendererSetExtruderColors)(JNIEnv* env, jobject, jfloatArray colors)
{
    guarded_void(env, [&] {
        std::vector<float> values(size_t(env->GetArrayLength(colors)));
        if (!values.empty())
            env->GetFloatArrayRegion(colors, 0, jsize(values.size()), values.data());
        SceneRenderer::get().set_extruder_colors(values);
    });
}

JNIEXPORT void JNICALL FN(previewSetLayerRange)(JNIEnv* env, jobject, jint first, jint last)
{
    guarded_void(env, [&] { SceneRenderer::get().preview().set_layer_range(uint32_t(first), uint32_t(last)); });
}

JNIEXPORT void JNICALL FN(previewSetMoveFraction)(JNIEnv* env, jobject, jfloat fraction)
{
    guarded_void(env, [&] { SceneRenderer::get().preview().set_move_fraction(fraction); });
}

JNIEXPORT void JNICALL FN(previewSetViewType)(JNIEnv* env, jobject, jint type)
{
    guarded_void(env, [&] { SceneRenderer::get().preview().set_view_type(type); });
}

JNIEXPORT void JNICALL FN(previewSetOptionVisible)(JNIEnv* env, jobject, jint option, jboolean visible)
{
    guarded_void(env, [&] { SceneRenderer::get().preview().set_option_visible(option, visible); });
}

JNIEXPORT void JNICALL FN(previewSetRoleVisible)(JNIEnv* env, jobject, jint role, jboolean visible)
{
    guarded_void(env, [&] { SceneRenderer::get().preview().set_role_visible(role, visible); });
}

JNIEXPORT jstring JNICALL FN(previewInfoJson)(JNIEnv* env, jobject)
{
    return guarded(env, [&] { return to_jstring(env, SceneRenderer::get().preview().info_json()); }, (jstring) nullptr);
}

} // extern "C"

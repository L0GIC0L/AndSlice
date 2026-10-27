# Builds libandslice.so: PrusaSlicer's slicing core (libslic3r), PrusaSlicer's
# G-code viewer (libvgcode) and the JNI bridge used by the Android app.
#
# Configure through scripts/build-native.sh, which passes the Android toolchain,
# PRUSASLICER_DIR and the prefix of the dependencies built by build-deps.sh.
cmake_minimum_required(VERSION 3.22)
project(AndSlice CXX C)

set(PRUSASLICER_DIR "" CACHE PATH "PrusaSlicer source tree")
if (NOT EXISTS "${PRUSASLICER_DIR}/src/libslic3r/CMakeLists.txt")
    message(FATAL_ERROR "PRUSASLICER_DIR does not point to a PrusaSlicer checkout: ${PRUSASLICER_DIR}")
endif ()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
list(APPEND CMAKE_MODULE_PATH "${PRUSASLICER_DIR}/cmake/modules")

# Version strings used by libslic3r_version.h.in.
include("${PRUSASLICER_DIR}/version.inc")

# Options PrusaSlicer's sub-projects read. Everything is linked statically and
# there is no wxWidgets GUI: the Android app provides the user interface.
set(SLIC3R_STATIC ON)
set(SLIC3R_GUI OFF)
set(SLIC3R_PCH OFF)
set(SLIC3R_ENC_CHECK OFF)
set(SLIC3R_BUILD_TESTS OFF)
set(CMAKE_FIND_PACKAGE_PREFER_CONFIG ON)

# Same flags PrusaSlicer's top-level CMakeLists sets for GCC/Clang builds.
add_compile_options(-fsigned-char -Werror=return-type -Wno-ignored-attributes
                    -Wno-deprecated-declarations -Wno-misleading-indentation)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-reorder")
add_definitions(-DwxUSE_UNICODE -D_UNICODE -DUNICODE -DWXINTL_NO_GETTEXT_MACRO)

# Helpers normally defined by PrusaSlicer's top-level CMakeLists. Source encoding
# checks and MSVC config remapping do not apply to this build.
function(encoding_check TARGET)
endfunction()
function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()

# ------------------------------------------------------------------ dependencies
set(Boost_USE_STATIC_LIBS ON)
find_package(Boost 1.83.0 REQUIRED COMPONENTS
    system filesystem thread log locale regex chrono atomic date_time iostreams nowide)
find_package(Eigen3 3.3.7 REQUIRED)
find_package(TBB REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT REQUIRED)
find_package(PNG REQUIRED)
find_package(JPEG REQUIRED)
find_package(cereal REQUIRED)
find_package(NLopt REQUIRED)
find_package(Qhull REQUIRED)
find_package(nlohmann_json REQUIRED)
find_package(NanoSVG REQUIRED)

add_library(boost_headeronly INTERFACE)
target_link_libraries(boost_headeronly INTERFACE Boost::boost)
add_library(boost_libs INTERFACE)
target_link_libraries(boost_libs INTERFACE boost_headeronly
    Boost::system Boost::filesystem Boost::thread Boost::log Boost::locale Boost::regex
    Boost::chrono Boost::atomic Boost::date_time Boost::iostreams Boost::nowide)

add_library(libexpat INTERFACE)
target_link_libraries(libexpat INTERFACE expat::expat)

add_library(libcereal INTERFACE)
target_link_libraries(libcereal INTERFACE cereal::cereal)

add_library(qhull INTERFACE)
target_link_libraries(qhull INTERFACE Qhull::qhullcpp Qhull::qhullstatic_r)

# libseqarrange (sequential print arrangement) needs the Z3 SMT solver. Only its
# headers are provided; ArrangeHelper.cpp is replaced by a stub below.
add_library(libseqarrange INTERFACE)
target_include_directories(libseqarrange INTERFACE "${PRUSASLICER_DIR}/src/libseqarrange/include")

# ------------------------------------------------------------------ PrusaSlicer
set(PS_SRC "${PRUSASLICER_DIR}/src")
set(PS_BUNDLED "${PRUSASLICER_DIR}/bundled_deps")

foreach (dep admesh miniz glu-libtess agg libigl libnest2d prusa_fdm_mixer)
    add_subdirectory("${PS_BUNDLED}/${dep}" "bundled_deps/${dep}" EXCLUDE_FROM_ALL)
endforeach ()

# Small libraries defined directly in bundled_deps/CMakeLists.txt.
add_library(semver STATIC "${PS_BUNDLED}/semver/semver.c")
target_include_directories(semver PUBLIC "${PS_BUNDLED}/semver")
add_library(qoi STATIC "${PS_BUNDLED}/qoi/qoilib.c")
target_include_directories(qoi PUBLIC "${PS_BUNDLED}/qoi")
add_library(fastfloat INTERFACE)
target_include_directories(fastfloat INTERFACE "${PS_BUNDLED}/fast_float")
add_library(int128 INTERFACE)
target_include_directories(int128 INTERFACE "${PS_BUNDLED}/int128")
add_library(localesutils STATIC "${PS_BUNDLED}/localesutils/LocalesUtils.cpp")
target_include_directories(localesutils PUBLIC "${PS_BUNDLED}/localesutils")
target_link_libraries(localesutils PRIVATE fastfloat)
add_library(ankerl INTERFACE)
target_include_directories(ankerl INTERFACE "${PS_BUNDLED}/ankerl")
add_library(stb_image INTERFACE)
target_include_directories(stb_image INTERFACE "${PS_BUNDLED}/stb_image")
add_library(tcbspan INTERFACE)
target_include_directories(tcbspan INTERFACE "${PS_BUNDLED}/tcbspan")

add_subdirectory("${PS_SRC}/clipper" "clipper" EXCLUDE_FROM_ALL)
add_subdirectory("${PS_SRC}/sla-time-estimates" "sla-time-estimates" EXCLUDE_FROM_ALL)
add_subdirectory("${PS_SRC}/libslic3r" "libslic3r" EXCLUDE_FROM_ALL)
add_subdirectory("${PS_SRC}/slic3r-arrange" "slic3r-arrange" EXCLUDE_FROM_ALL)
add_subdirectory("${PS_SRC}/slic3r-arrange-wrapper" "slic3r-arrange-wrapper" EXCLUDE_FROM_ALL)

# Android-specific replacements for the parts of libslic3r that need OpenVDB
# (SLA hollowing) or Z3 (sequential arrangement). Neither is used for FDM slicing.
get_target_property(_slic3r_sources libslic3r SOURCES)
list(REMOVE_ITEM _slic3r_sources ArrangeHelper.cpp)
set_target_properties(libslic3r PROPERTIES SOURCES "${_slic3r_sources}")
target_sources(libslic3r PRIVATE
    src/stubs/ArrangeHelperStub.cpp
    src/stubs/OpenVDBUtilsStub.cpp)
target_link_libraries(libslic3r PUBLIC NanoSVG::nanosvg NanoSVG::nanosvgrast)

# ------------------------------------------------------------------ libvgcode
# PrusaSlicer's G-code viewer, built for OpenGL ES 3.
set(VGCODE_DIR "${PS_SRC}/libvgcode")
file(GLOB VGCODE_SOURCES "${VGCODE_DIR}/src/*.cpp")
add_library(libvgcode STATIC ${VGCODE_SOURCES})
target_include_directories(libvgcode PUBLIC "${VGCODE_DIR}/include" PRIVATE "${VGCODE_DIR}/glad/include")
target_compile_definitions(libvgcode PUBLIC ENABLE_OPENGL_ES=1)
if (ANDROID)
    # The GL loader resolves entry points through the system EGL.
    target_sources(libvgcode PRIVATE "${VGCODE_DIR}/glad/src/gles2.c")
    target_compile_definitions(libvgcode PRIVATE GLAD_GLES2_USE_SYSTEM_EGL=1)
    target_link_libraries(libvgcode PUBLIC EGL GLESv3 dl)
endif ()

# ------------------------------------------------------------------ AndSlice
# Engine shared by the Android library and the host test tool.
add_library(andslice_core STATIC
    src/Engine.cpp
    src/GCodePreview.cpp
    src/Thumbnail.cpp)
target_include_directories(andslice_core PUBLIC src "${PS_SRC}")
target_link_libraries(andslice_core PUBLIC libslic3r slic3r-arrange-wrapper libvgcode)

if (ANDROID)
    add_library(andslice SHARED
        src/jni_bridge.cpp
        src/PreviewRenderer.cpp
        src/SceneRenderer.cpp)
    target_link_libraries(andslice PRIVATE andslice_core GLESv3 EGL log android)
    target_link_options(andslice PRIVATE
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
    set_target_properties(andslice PROPERTIES CXX_VISIBILITY_PRESET hidden)
else ()
    # Command-line harness used to test slicing on the build machine.
    add_executable(andslice_cli src/cli.cpp)
    target_link_libraries(andslice_cli PRIVATE andslice_core ${CMAKE_DL_LIBS})
endif ()

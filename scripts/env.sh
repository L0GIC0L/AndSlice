# Shared settings for the native build scripts. Source this file, don't run it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# PrusaSlicer release whose slicing core (libslic3r) we build.
PRUSASLICER_TAG="version_2.9.6"
PRUSASLICER_COMMIT="b028299c770b8380ee81c921a2867d522f288123"
PRUSASLICER_DIR="$ROOT/third_party/PrusaSlicer"

NDK_VERSION="27.3.13750724"
SDK_CMAKE_VERSION="3.31.6"
ANDROID_API=28

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
# The pinned NDK from the SDK; ANDSLICE_NDK overrides it.
NDK="${ANDSLICE_NDK:-$ANDROID_HOME/ndk/$NDK_VERSION}"

# Prefer the CMake shipped with the Android SDK so local and CI builds match.
if [ -x "$ANDROID_HOME/cmake/$SDK_CMAKE_VERSION/bin/cmake" ]; then
    CMAKE="$ANDROID_HOME/cmake/$SDK_CMAKE_VERSION/bin/cmake"
    NINJA="$ANDROID_HOME/cmake/$SDK_CMAKE_VERSION/bin/ninja"
else
    CMAKE="$(command -v cmake)"
    NINJA="$(command -v ninja)"
fi

JOBS="${JOBS:-$(nproc)}"
BUILD_ROOT="${BUILD_ROOT:-$ROOT/build}"
DOWNLOAD_DIR="${DOWNLOAD_DIR:-$BUILD_ROOT/downloads}"

# abi_triple <abi> -> autotools host triple ("host" builds for this machine,
# used for the command-line test harness).
abi_triple() {
    case "$1" in
        host)        echo "" ;;
        arm64-v8a)   echo aarch64-linux-android ;;
        x86_64)      echo x86_64-linux-android ;;
        armeabi-v7a) echo armv7a-linux-androideabi ;;
        *) echo "unsupported ABI: $1" >&2; return 1 ;;
    esac
}

deps_prefix() { echo "$BUILD_ROOT/deps/$1/install"; }

# Fills the ANDROID_CMAKE_ARGS array with the CMake arguments shared by every
# cross-compiled project.
set_android_cmake_args() {
    local abi="$1" prefix
    prefix="$(deps_prefix "$abi")"
    if [ "$abi" = host ]; then
        ANDROID_CMAKE_ARGS=(
            -G Ninja
            "-DCMAKE_MAKE_PROGRAM=$NINJA"
            "-DCMAKE_BUILD_TYPE=Release"
            "-DCMAKE_PREFIX_PATH=$prefix"
            "-DBUILD_SHARED_LIBS=OFF"
            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
            "-DBUILD_TESTING=OFF"
        )
        return
    fi
    ANDROID_CMAKE_ARGS=(
        -G Ninja
        "-DCMAKE_MAKE_PROGRAM=$NINJA"
        "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake"
        "-DANDROID_ABI=$abi"
        "-DANDROID_PLATFORM=android-$ANDROID_API"
        "-DANDROID_STL=c++_static"
        "-DCMAKE_BUILD_TYPE=Release"
        # The NDK toolchain adds -g; without debug info the build and the CI caches are a fraction of the size.
        "-DCMAKE_C_FLAGS=-g0"
        "-DCMAKE_CXX_FLAGS=-g0"
        "-DCMAKE_PREFIX_PATH=$prefix"
        "-DCMAKE_FIND_ROOT_PATH=$prefix"
        "-DBUILD_SHARED_LIBS=OFF"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DBUILD_TESTING=OFF"
    )
}

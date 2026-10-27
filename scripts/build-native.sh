#!/usr/bin/env bash
# Build libandslice.so (PrusaSlicer core + JNI bridge) for one Android ABI and
# copy it into app/src/main/jniLibs/<abi>/.
#
#   scripts/build-native.sh <abi> [cmake target]
#
# Run scripts/build-deps.sh <abi> first. Set CCACHE=1 to compile through ccache.
set -euo pipefail
source "$(dirname "$0")/env.sh"

ABI="${1:?usage: build-native.sh <abi> [target]}"
TARGET="${2:-andslice}"
PREFIX="$(deps_prefix "$ABI")"
BUILD_DIR="$BUILD_ROOT/native/$ABI"
set_android_cmake_args "$ABI"

"$ROOT/scripts/fetch-prusaslicer.sh"
[ -f "$PREFIX/.stamps/libbgcode-6f4ad7c" ] || { echo "Dependencies for $ABI are missing; run scripts/build-deps.sh $ABI" >&2; exit 1; }

launcher=()
if [ "${CCACHE:-0}" = "1" ] && command -v ccache > /dev/null; then
    launcher=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

"$CMAKE" -S "$ROOT/native" -B "$BUILD_DIR" "${ANDROID_CMAKE_ARGS[@]}" "${launcher[@]}" \
    "-DPRUSASLICER_DIR=$PRUSASLICER_DIR"
"$CMAKE" --build "$BUILD_DIR" --target "$TARGET" --parallel "$JOBS"

if [ "$TARGET" = "andslice" ]; then
    out="$ROOT/app/src/main/jniLibs/$ABI"
    mkdir -p "$out"
    cp "$BUILD_DIR/libandslice.so" "$out/"
    echo "Installed $out/libandslice.so ($(du -h "$out/libandslice.so" | cut -f1))"
fi

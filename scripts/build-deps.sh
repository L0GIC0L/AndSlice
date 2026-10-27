#!/usr/bin/env bash
# Cross-compile the third-party libraries PrusaSlicer's slicing core needs.
#
#   scripts/build-deps.sh <abi>      abi: arm64-v8a | x86_64 | armeabi-v7a | host
#
# "host" builds the same libraries for the build machine; it is used by the
# command-line test harness (scripts/test-host.sh).
#
# Everything is built as static libraries into build/deps/<abi>/install.
# Versions follow PrusaSlicer's own deps/ recipes where Android allows it.
# Each package leaves a stamp file, so re-running only builds what is missing.
set -euo pipefail
source "$(dirname "$0")/env.sh"

ABI="${1:?usage: build-deps.sh <abi>}"
TRIPLE="$(abi_triple "$ABI")"
PREFIX="$(deps_prefix "$ABI")"
WORK="$BUILD_ROOT/deps/$ABI/work"
STAMPS="$PREFIX/.stamps"
SRC_CACHE="$DOWNLOAD_DIR/src"
set_android_cmake_args "$ABI"

if [ "$ABI" != host ] && [ ! -d "$NDK" ]; then
    echo "Android NDK not found at $NDK" >&2; exit 1
fi
"$ROOT/scripts/fetch-prusaslicer.sh"
mkdir -p "$PREFIX" "$WORK" "$STAMPS" "$DOWNLOAD_DIR" "$SRC_CACHE"

log() { echo "==> [$ABI] $*"; }
done_already() { [ -f "$STAMPS/$1" ]; }
mark_done() { touch "$STAMPS/$1"; }

# fetch_url <name> <url> <sha256>: download + verify + unpack into $SRC_CACHE/<name>
fetch_url() {
    local name="$1" url="$2" sha="$3" file
    file="$DOWNLOAD_DIR/$(basename "$url")"
    if [ -d "$SRC_CACHE/$name" ]; then return; fi
    if [ ! -f "$file" ]; then
        curl -fsSL --retry 5 --retry-delay 5 -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    echo "$sha  $file" | sha256sum -c --quiet - || { rm -f "$file"; echo "checksum mismatch: $url" >&2; exit 1; }
    mkdir -p "$SRC_CACHE/$name.tmp"
    tar -xf "$file" -C "$SRC_CACHE/$name.tmp" --strip-components=1
    mv "$SRC_CACHE/$name.tmp" "$SRC_CACHE/$name"
}

# fetch_git <name> <github owner/repo> <tag or commit>: shallow clone into $SRC_CACHE/<name>
fetch_git() {
    local name="$1" repo="$2" ref="$3" dir="$SRC_CACHE/$1"
    if [ -d "$dir" ]; then return; fi
    rm -rf "$dir.tmp"
    git init -q "$dir.tmp"
    git -C "$dir.tmp" fetch -q --depth 1 "https://github.com/$repo.git" "$ref"
    git -C "$dir.tmp" -c advice.detachedHead=false checkout -q FETCH_HEAD
    mv "$dir.tmp" "$dir"
}

# cmake_dep <stamp> <source dir> [extra cmake args...]
cmake_dep() {
    local stamp="$1" src="$2"; shift 2
    done_already "$stamp" && return
    log "building $stamp"
    rm -rf "$WORK/$stamp"
    "$CMAKE" -S "$src" -B "$WORK/$stamp" "${ANDROID_CMAKE_ARGS[@]}" \
        "-DCMAKE_INSTALL_PREFIX=$PREFIX" "$@" > "$WORK/$stamp.configure.log" 2>&1 \
        || { tail -50 "$WORK/$stamp.configure.log"; exit 1; }
    "$CMAKE" --build "$WORK/$stamp" --parallel "$JOBS" > "$WORK/$stamp.build.log" 2>&1 \
        || { tail -80 "$WORK/$stamp.build.log"; exit 1; }
    "$CMAKE" --install "$WORK/$stamp" > "$WORK/$stamp.install.log" 2>&1 \
        || { tail -30 "$WORK/$stamp.install.log"; exit 1; }
    rm -rf "$WORK/$stamp"
    mark_done "$stamp"
}

# Compiler environment for autotools projects (GMP, MPFR).
TOOLBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
autotools_env() {
    export CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC"
    [ "$ABI" = host ] && return
    export CC="$TOOLBIN/${TRIPLE}${ANDROID_API}-clang"
    export CXX="$TOOLBIN/${TRIPLE}${ANDROID_API}-clang++"
    export AR="$TOOLBIN/llvm-ar" RANLIB="$TOOLBIN/llvm-ranlib" NM="$TOOLBIN/llvm-nm"
    export STRIP="$TOOLBIN/llvm-strip" LD="$TOOLBIN/ld.lld"
}

# autotools_dep <stamp> <source dir> [extra configure args...]
autotools_dep() {
    local stamp="$1" src="$2"; shift 2
    done_already "$stamp" && return
    log "building $stamp"
    rm -rf "$WORK/$stamp" && mkdir -p "$WORK/$stamp"
    (
        cd "$WORK/$stamp"
        autotools_env
        local host_arg=()
        [ -n "$TRIPLE" ] && host_arg=(--host="$TRIPLE")
        "$src/configure" "${host_arg[@]}" --prefix="$PREFIX" \
            --enable-static --disable-shared --with-pic "$@" > configure.log 2>&1 \
            || { tail -50 configure.log; exit 1; }
        make -j"$JOBS" > build.log 2>&1 || { tail -80 build.log; exit 1; }
        make install > install.log 2>&1 || { tail -30 install.log; exit 1; }
    )
    rm -rf "$WORK/$stamp"
    mark_done "$stamp"
}

# ---------------------------------------------------------------- Boost
fetch_url boost-1.83.0 \
    https://github.com/boostorg/boost/releases/download/boost-1.83.0/boost-1.83.0.tar.xz \
    c5a0688e1f0c05f354bbd0b32244d36085d9ffc9f932e8a18983a9908096f614
cmake_dep boost-1.83.0 "$SRC_CACHE/boost-1.83.0" \
    "-DBOOST_EXCLUDE_LIBRARIES=contract;fiber;numpy;python;mpi;graph_parallel;stacktrace;wave;test" \
    -DBOOST_LOCALE_ENABLE_ICU=OFF \
    -DBOOST_LOCALE_ENABLE_ICONV=ON \
    -DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF \
    -DBOOST_IOSTREAMS_ENABLE_BZIP2=OFF \
    -DBOOST_IOSTREAMS_ENABLE_LZMA=OFF

# ---------------------------------------------------------------- oneTBB
fetch_git onetbb-2021.13.0 uxlfoundation/oneTBB v2021.13.0
cmake_dep onetbb-2021.13.0 "$SRC_CACHE/onetbb-2021.13.0" \
    -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBB_STRICT=OFF -DTBBMALLOC_PROXY_BUILD=OFF

# ---------------------------------------------------------------- GMP + MPFR (for CGAL)
fetch_url gmp-6.3.0 https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz \
    a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898
autotools_dep gmp-6.3.0 "$SRC_CACHE/gmp-6.3.0" --enable-cxx

fetch_url mpfr-4.2.1 https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.xz \
    277807353a6726978996945af13e52829e3abd7a9a5b7fb2793894e18f1fcbb2
autotools_dep mpfr-4.2.1 "$SRC_CACHE/mpfr-4.2.1" --with-gmp="$PREFIX"

# ---------------------------------------------------------------- header-only libraries
fetch_url cgal-5.6.2 https://github.com/CGAL/cgal/releases/download/v5.6.2/CGAL-5.6.2.tar.xz \
    458f60df8e8f1f2fdad93c8f24e1aa8f4b095cc61a14fac81b90680d7306a42e
cmake_dep cgal-5.6.2 "$SRC_CACHE/cgal-5.6.2" -DWITH_CGAL_Qt5=OFF -DWITH_CGAL_ImageIO=OFF

fetch_url eigen-3.4.0 https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz \
    8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72
cmake_dep eigen-3.4.0 "$SRC_CACHE/eigen-3.4.0" -DEIGEN_BUILD_DOC=OFF -DEIGEN_BUILD_PKGCONFIG=OFF

fetch_git cereal-1.3.2 USCiLab/cereal v1.3.2
cmake_dep cereal-1.3.2 "$SRC_CACHE/cereal-1.3.2" \
    -DJUST_INSTALL_CEREAL=ON -DSKIP_PERFORMANCE_COMPARISON=ON -DBUILD_TESTS=OFF

fetch_git json-3.11.3 nlohmann/json v3.11.3
cmake_dep json-3.11.3 "$SRC_CACHE/json-3.11.3" -DJSON_BuildTests=OFF -DJSON_Install=ON

fetch_git nanosvg-abcd277 fltk/nanosvg abcd277ea45e9098bed752cf9c6875b533c0892f
cmake_dep nanosvg-abcd277 "$SRC_CACHE/nanosvg-abcd277"

# ---------------------------------------------------------------- compiled libraries
fetch_url expat-2.6.4 https://github.com/libexpat/libexpat/releases/download/R_2_6_4/expat-2.6.4.tar.xz \
    a695629dae047055b37d50a0ff4776d1d45d0a4c842cf4ccee158441f55ff7ee
cmake_dep expat-2.6.4 "$SRC_CACHE/expat-2.6.4" \
    -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TESTS=OFF \
    -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_PKGCONFIG=OFF -DEXPAT_SHARED_LIBS=OFF

fetch_git libpng-1.6.44 pnggroup/libpng v1.6.44
cmake_dep libpng-1.6.44 "$SRC_CACHE/libpng-1.6.44" \
    -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_EXECUTABLES=OFF

fetch_git libjpeg-turbo-3.0.4 libjpeg-turbo/libjpeg-turbo 3.0.4
cmake_dep libjpeg-turbo-3.0.4 "$SRC_CACHE/libjpeg-turbo-3.0.4" \
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF

fetch_git qhull-8.1-alpha3 qhull/qhull v8.1-alpha3
cmake_dep qhull-8.1-alpha3 "$SRC_CACHE/qhull-8.1-alpha3" \
    -DBUILD_APPLICATIONS=OFF -DBUILD_STATIC_LIBS=ON -DQHULL_ENABLE_TESTING=OFF

fetch_git nlopt-2.7.1 stevengj/nlopt v2.7.1
cmake_dep nlopt-2.7.1 "$SRC_CACHE/nlopt-2.7.1" \
    -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF \
    -DNLOPT_SWIG=OFF -DNLOPT_FORTRAN=OFF -DNLOPT_TESTS=OFF

# heatshrink has no CMake build upstream; PrusaSlicer ships one.
fetch_git heatshrink-0.4.1 atomicobject/heatshrink v0.4.1
cp "$PRUSASLICER_DIR/deps/+heatshrink/CMakeLists.txt" \
   "$PRUSASLICER_DIR/deps/+heatshrink/Config.cmake.in" "$SRC_CACHE/heatshrink-0.4.1/"
cmake_dep heatshrink-0.4.1 "$SRC_CACHE/heatshrink-0.4.1"

# Same libbgcode commit PrusaSlicer 2.9 pins in deps/+LibBGCode.
fetch_git libbgcode-6f4ad7c prusa3d/libbgcode 6f4ad7ce6b0e638b760199d6611039a610a5a479
cmake_dep libbgcode-6f4ad7c "$SRC_CACHE/libbgcode-6f4ad7c" \
    -DLibBGCode_BUILD_TESTS=OFF -DLibBGCode_BUILD_CMD_TOOL=OFF

log "all dependencies installed in $PREFIX"

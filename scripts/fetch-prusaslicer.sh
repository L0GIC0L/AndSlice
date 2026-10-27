#!/usr/bin/env bash
# Fetch the pinned PrusaSlicer release into third_party/PrusaSlicer.
set -euo pipefail
source "$(dirname "$0")/env.sh"

# Applies patches/*.patch, skipping the ones already applied.
apply_patches() {
    local p
    for p in "$ROOT"/patches/*.patch; do
        [ -e "$p" ] || continue
        if git -C "$PRUSASLICER_DIR" apply --reverse --check "$p" 2> /dev/null; then
            continue
        fi
        echo "Applying $(basename "$p")"
        git -C "$PRUSASLICER_DIR" apply "$p"
    done
}

if [ -d "$PRUSASLICER_DIR/.git" ]; then
    have="$(git -C "$PRUSASLICER_DIR" rev-parse HEAD)"
    if [ "$have" = "$PRUSASLICER_COMMIT" ]; then
        echo "PrusaSlicer $PRUSASLICER_TAG already present"
        apply_patches
        exit 0
    fi
    echo "PrusaSlicer checkout is at $have, expected $PRUSASLICER_COMMIT; re-fetching"
    rm -rf "$PRUSASLICER_DIR"
fi

mkdir -p "$(dirname "$PRUSASLICER_DIR")"
git -c advice.detachedHead=false clone --depth 1 --branch "$PRUSASLICER_TAG" \
    https://github.com/prusa3d/PrusaSlicer.git "$PRUSASLICER_DIR"

have="$(git -C "$PRUSASLICER_DIR" rev-parse HEAD)"
if [ "$have" != "$PRUSASLICER_COMMIT" ]; then
    echo "Unexpected PrusaSlicer commit $have (wanted $PRUSASLICER_COMMIT)" >&2
    exit 1
fi

apply_patches

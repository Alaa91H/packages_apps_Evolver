#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORCE=0

if [[ "${1:-}" == "--force" ]]; then
    FORCE=1
    shift
fi

TARGET="${1:-}"
if [[ -z "$TARGET" ]]; then
    echo "Usage: bash frameworks_base_patch/apply.sh [--force] /path/to/frameworks/base" >&2
    exit 2
fi

if [[ ! -d "$TARGET/.git" ]]; then
    echo "Error: '$TARGET' is not a frameworks/base Git checkout." >&2
    exit 2
fi

declare -A EXPECTED=(
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/CutoutProgressSettings.java"]="98068237026dd7b0c4a449f5a165f9208eb9262d"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/DownloadStateTracker.java"]="be2e4af909329bb55616d9e9a377346c1344c4bc"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/CutoutProgressController.java"]="53a7a81cc8866b167c4ddba2797188a2287cf70b"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicRingController.java"]="01a827761f7891d65d9e932b48a5fde280a44b23"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicRingColorManager.java"]="8f4a35dc22a2ae4f876d17308f6d83e8e99775e7"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicProgressTracker.java"]="e7d0f4889476f8be9ae8cbd44dffb3e98e5ba860"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CutoutRingView.java"]="bf86929ae363d2895421d65ed8fd50a79e62c70e"
)

NEW_FILES=(
    "packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CameraCutoutGeometryResolver.java"
)

echo "Checking frameworks/base cutout-progress baseline..."
for rel in "${!EXPECTED[@]}"; do
    if [[ ! -f "$TARGET/$rel" ]]; then
        echo "Error: missing target file: $rel" >&2
        exit 1
    fi
    current="$(git -C "$TARGET" hash-object "$rel")"
    if [[ "$current" != "${EXPECTED[$rel]}" && "$FORCE" -ne 1 ]]; then
        echo "Error: upstream file changed: $rel" >&2
        echo "  expected: ${EXPECTED[$rel]}" >&2
        echo "  current : $current" >&2
        echo "Rebase the staged implementation first, or rerun with --force only after manual review." >&2
        exit 1
    fi
done

for rel in "${NEW_FILES[@]}"; do
    if [[ -e "$TARGET/$rel" && "$FORCE" -ne 1 ]]; then
        echo "Error: new staged file already exists upstream: $rel" >&2
        echo "Review/rebase the integration, or rerun with --force only after manual review." >&2
        exit 1
    fi
done

echo "Applying staged Cutout Progress Pro files..."
for rel in "${!EXPECTED[@]}"; do
    install -D -m 0644 "$SCRIPT_DIR/$rel" "$TARGET/$rel"
done
for rel in "${NEW_FILES[@]}"; do
    install -D -m 0644 "$SCRIPT_DIR/$rel" "$TARGET/$rel"
done

echo
echo "Applied successfully. Review with:"
echo "  git -C '$TARGET' diff -- packages/SystemUI/src/com/android/systemui/cutoutprogress"
echo
git -C "$TARGET" diff --stat -- packages/SystemUI/src/com/android/systemui/cutoutprogress

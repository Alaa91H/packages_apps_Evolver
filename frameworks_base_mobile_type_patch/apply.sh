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
    echo "Usage: bash frameworks_base_mobile_type_patch/apply.sh [--force] /path/to/frameworks/base" >&2
    exit 2
fi

if ! git -C "$TARGET" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: '$TARGET' is not a Git worktree." >&2
    exit 2
fi

if [[ "$(git -C "$TARGET" rev-parse --show-toplevel)" != "$(cd "$TARGET" && pwd -P)" ]]; then
    echo "Error: '$TARGET' is not the root of the frameworks/base Git worktree." >&2
    exit 2
fi

REL_FILES=(
    "packages/SystemUI/src/com/android/systemui/statusbar/pipeline/mobile/ui/view/ModernStatusBarMobileView.kt"
    "packages/SystemUI/res-keyguard/layout/status_bar_mobile_signal_group_inner.xml"
)
EXPECTED_SHAS=(
    "c74da39a327ccf24531e786ca060489a55f4e143"
    "14e0b5bed493fbbad5669110877910960447a17b"
)

CURRENT_SHAS=()
DESIRED_SHAS=()

for i in "${!REL_FILES[@]}"; do
    rel="${REL_FILES[$i]}"
    expected="${EXPECTED_SHAS[$i]}"
    staged="$SCRIPT_DIR/$rel"

    if [[ ! -f "$staged" ]]; then
        echo "Error: missing staged file: $rel" >&2
        exit 1
    fi
    if [[ ! -f "$TARGET/$rel" ]]; then
        echo "Error: missing target file: $rel" >&2
        exit 1
    fi

    current="$(git -C "$TARGET" hash-object "$rel")"
    desired="$(git hash-object "$staged")"
    CURRENT_SHAS+=("$current")
    DESIRED_SHAS+=("$desired")

    if [[ "$current" != "$expected" && "$current" != "$desired" && "$FORCE" -ne 1 ]]; then
        echo "Error: upstream/local SystemUI source changed: $rel" >&2
        echo "  baseline: $expected" >&2
        echo "  staged  : $desired" >&2
        echo "  current : $current" >&2
        echo "Rebase/review the staged implementation first, or use --force only after manual review." >&2
        exit 1
    fi
done

echo "Applying staged mobile network type presentation..."
for rel in "${REL_FILES[@]}"; do
    install -D -m 0644 "$SCRIPT_DIR/$rel" "$TARGET/$rel"
done

echo "Checking resulting diff for whitespace errors..."
git -C "$TARGET" diff --check -- "${REL_FILES[@]}"

echo
echo "Applied successfully. Review with:"
printf "  git -C '%s' diff --" "$TARGET"
printf " '%s'" "${REL_FILES[@]}"
printf "\n\n"
git -C "$TARGET" diff --stat -- "${REL_FILES[@]}"

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
    echo "Usage: bash frameworks_base_network_traffic_patch/apply.sh [--force] /path/to/frameworks/base" >&2
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

REL="packages/SystemUI/src/com/android/systemui/statusbar/NetworkTraffic.java"
EXPECTED="415011f8046cd15f0a15176acd0df53967789baa"
STAGED="$SCRIPT_DIR/$REL"

if [[ ! -f "$STAGED" ]]; then
    echo "Error: missing staged file: $REL" >&2
    exit 1
fi
if [[ ! -f "$TARGET/$REL" ]]; then
    echo "Error: missing target file: $REL" >&2
    exit 1
fi

current="$(git -C "$TARGET" hash-object "$REL")"
desired="$(git hash-object "$STAGED")"

# Safe idempotent re-runs are allowed. Any third state requires explicit manual review.
if [[ "$current" != "$EXPECTED" && "$current" != "$desired" && "$FORCE" -ne 1 ]]; then
    echo "Error: upstream/local NetworkTraffic.java changed." >&2
    echo "  baseline: $EXPECTED" >&2
    echo "  staged  : $desired" >&2
    echo "  current : $current" >&2
    echo "Rebase/review the staged implementation first, or use --force only after manual review." >&2
    exit 1
fi

echo "Applying staged Network Traffic SystemUI implementation..."
install -D -m 0644 "$STAGED" "$TARGET/$REL"

echo "Checking resulting diff for whitespace errors..."
git -C "$TARGET" diff --check -- "$REL"

echo
echo "Applied successfully. Review with:"
echo "  git -C '$TARGET' diff -- '$REL'"
echo
git -C "$TARGET" diff --stat -- "$REL"

#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EVOLVER_XML = ROOT / "res/xml/dynamic_bar.xml"
STRINGS_EN = ROOT / "res/values/evolution_strings.xml"
STRINGS_AR = ROOT / "res/values-ar/evolution_strings.xml"
ARRAYS = ROOT / "res/values/evolution_arrays.xml"
APPLY = ROOT / "frameworks_base_patch/apply.sh"

SYSTEMUI = ROOT / "frameworks_base_patch/packages/SystemUI/src/com/android/systemui"
STATUS_ROOT = SYSTEMUI / "statusbar/pipeline/shared/ui/composable/StatusBarRoot.kt"
SETTINGS = SYSTEMUI / "axdynamicbar/domain/AxDynamicBarSettings.kt"
INTERACTOR = SYSTEMUI / "axdynamicbar/domain/AxDynamicBarInteractor.kt"
SYSTEM_MANAGER = SYSTEMUI / "axdynamicbar/data/source/SystemIslandManager.kt"
VIEW_MODEL = SYSTEMUI / "axdynamicbar/ui/AxDynamicBarChipViewModel.kt"
EXPANDED = SYSTEMUI / "axdynamicbar/ui/AxDynamicBarExpandedPanel.kt"
LEGACY_CHIP = SYSTEMUI / "axdynamicbar/ui/compose/AxDynamicBarChip.kt"
HOST = SYSTEMUI / "axdynamicbar/ui/compose/DynamicBarCutoutHost.kt"
LAYOUT = SYSTEMUI / "axdynamicbar/ui/layout/DynamicBarLayoutState.kt"
KEYGUARD = SYSTEMUI / "keyguard/ui/view/layout/sections/AxDynamicBarKeyguardChipSection.kt"
SWIPE_DEMO = ROOT / "src/org/evolution/settings/fragments/statusbar/DynamicBarChipSwipeDemoView.kt"
KEYGUARD_DEMO = ROOT / "src/org/evolution/settings/fragments/statusbar/DynamicBarKeyguardDemoView.kt"
RESOLVER = SYSTEMUI / "cutoutprogress/ring/CameraCutoutGeometryResolver.java"

REQUIRED_KEYS = {
    "ax_dynamic_bar_cutout_alignment",
    "ax_dynamic_bar_island_size",
    "ax_dynamic_bar_landscape_mode",
    "ax_dynamic_bar_debug_bounds",
}

REQUIRED_ARRAYS = {
    "dynamic_bar_cutout_alignment_entries",
    "dynamic_bar_cutout_alignment_values",
    "dynamic_bar_island_size_entries",
    "dynamic_bar_island_size_values",
    "dynamic_bar_landscape_mode_entries",
    "dynamic_bar_landscape_mode_values",
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def xml_names(path: Path, tag: str) -> set[str]:
    root = ET.parse(path).getroot()
    return {
        elem.attrib["name"]
        for elem in root.findall(tag)
        if "name" in elem.attrib
    }


for path in (
    EVOLVER_XML,
    STRINGS_EN,
    STRINGS_AR,
    ARRAYS,
    APPLY,
    STATUS_ROOT,
    SETTINGS,
    INTERACTOR,
    SYSTEM_MANAGER,
    VIEW_MODEL,
    EXPANDED,
    LEGACY_CHIP,
    HOST,
    LAYOUT,
    KEYGUARD,
    SWIPE_DEMO,
    KEYGUARD_DEMO,
    RESOLVER,
):
    if not path.exists():
        fail(f"missing {path.relative_to(ROOT)}")

xml_text = read(EVOLVER_XML)
for key in REQUIRED_KEYS:
    if f'android:key="{key}"' not in xml_text:
        fail(f"dynamic_bar.xml missing {key}")

array_names = xml_names(ARRAYS, "string-array")
missing_arrays = REQUIRED_ARRAYS - array_names
if missing_arrays:
    fail("missing Dynamic Bar arrays: " + ", ".join(sorted(missing_arrays)))

english = xml_names(STRINGS_EN, "string")
arabic = xml_names(STRINGS_AR, "string")
dynamic_english = {name for name in english if name.startswith("dynamic_bar_")}
missing_arabic = dynamic_english - arabic
if missing_arabic:
    fail("Arabic Dynamic Bar string parity failed: " + ", ".join(sorted(missing_arabic)))

settings_text = read(SETTINGS)
for key in REQUIRED_KEYS:
    if key not in settings_text:
        fail(f"SystemUI settings missing {key}")
if "if (contentResolver !== secureResolver)" not in settings_text:
    fail("Dynamic Bar settings teardown may double-unregister the shared ContentObserver")

system_manager_text = read(SYSTEM_MANAGER)
for token in (
    "EXTRA_DYNAMIC_BAR_SELF_COPY",
    "clipSource == context.packageName",
    "ClipDescription.EXTRA_IS_SENSITIVE",
    "desc.timestamp",
    "lastClipboardToken",
    "loadThumbnail(",
    "withTimeoutOrNull(CLIPBOARD_IMAGE_TIMEOUT_MS)",
    "clipboardGeneration",
    "commitClipboardEvent(",
    "PersistableBundle",
    "UserTracker",
    "UserScopedService<ClipboardManager>",
    "clipboardUserCallback",
    "switchClipboardUser(",
    "writeClipboardHistory(oldState, oldSnapshot)",
    "ACTIVE_CLIPBOARD_DIR",
    "createActiveClipboardLease(",
    "cleanupActiveClipboardLeases(",
    "prunedBrokenImage",
):
    if token not in system_manager_text:
        fail(f"Dynamic Bar clipboard hardening missing {token}")

for forbidden in (
    "suppressNextClipEvent",
    "DUPLICATE_CLIP_WINDOW_MS",
    "SystemClock.elapsedRealtime()",
    "coerceToText(context)",
    "ImageDecoder",
    "private val clipboardManager: ClipboardManager by lazy",
    "private val prefs by lazy",
    "private val clipboardCacheDir: File by lazy",
):
    if forbidden in system_manager_text:
        fail(f"Dynamic Bar clipboard still contains legacy behavior: {forbidden}")

if not re.search(
    r"clipSource\s*==\s*context\.packageName\s*&&\s*desc\.extras\?\.getBoolean\(EXTRA_DYNAMIC_BAR_SELF_COPY",
    system_manager_text,
    re.S,
):
    fail("Dynamic Bar self-copy marker is not source-attributed to SystemUI")

if "if (cachedUri == null)" not in system_manager_text:
    fail("Dynamic Bar can still persist a broken image history item")

if "if (isImage && imageUri == null)" not in system_manager_text:
    fail("Dynamic Bar restore does not prune missing clipboard image cache files")

interactor_text = read(INTERACTOR)
if "!(onKeyguard && e is IslandEvent.Clipboard)" not in interactor_text:
    fail("Dynamic Bar clipboard content may be exposed on the keyguard")

status_root = read(STATUS_ROOT)
if "DynamicBarCutoutHost(" not in status_root:
    fail("StatusBarRoot does not host DynamicBarCutoutHost")
if re.search(r"\bAxDynamicBarChip\(", status_root):
    fail("StatusBarRoot still renders the legacy start-side AxDynamicBarChip")

host_text = read(HOST)
for token in (
    "CameraCutoutGeometryResolver",
    "LocalLayoutDirection",
    "LayoutDirection.Rtl",
    "DynamicBarLayoutCalculator.calculate",
    "updateDynamicBarAnchor",
    "PillEventIcon",
    "PillEventText",
    "rememberOccupiedBounds",
    "if (width.value < 24f) return",
    "width.value >= 48f",
):
    if token not in host_text:
        fail(f"cutout host missing {token}")

layout_text = read(LAYOUT)
for token in (
    "leftWingWidthPx",
    "rightWingWidthPx",
    "cameraSlotWidthPx",
    "hasPhysicalCutout",
    "LANDSCAPE_COMPACT",
    "ALIGNMENT_CENTER",
    "occupiedBounds",
    "leftContentEdge",
    "rightContentEdge",
    "physicalCutoutEligible",
    "cameraSlotLeft",
    "cameraSlotRight",
    "usableWidthPx",
    "wingBudgetPx",
):
    if token not in layout_text:
        fail(f"layout calculator missing {token}")

if not re.search(
    r"if\s*\(isLandscape\)\s*\{\s*landscapeMode\s*==\s*LANDSCAPE_ALWAYS\s*\|\|\s*topCutout\s*\}\s*else\s*\{\s*topCutout\s*\}",
    layout_text,
    re.S,
):
    fail("portrait Dynamic Bar may anchor to a non-status-bar cutout")

resolver_text = read(RESOLVER)
for token in (
    "public final class CameraCutoutGeometryResolver",
    "public static final class ResolvedGeometry",
    "public ResolvedGeometry resolve",
    "expectedCameraEdgeDistance",
    "case Surface.ROTATION_90",
    "case Surface.ROTATION_180",
    "case Surface.ROTATION_270",
):
    if token not in resolver_text:
        fail(f"camera resolver is not shareable: {token}")

expanded_text = read(EXPANDED)
if "cutoutAnchorBottomPx" not in expanded_text:
    fail("expanded panel is not anchored below the compact island")
if "chipCenterXFraction" not in expanded_text:
    fail("expanded panel does not animate from the camera X anchor")

keyguard_text = read(KEYGUARD)
if "DynamicBarCutoutHost(" not in keyguard_text or "keyguardMode = true" not in keyguard_text:
    fail("keyguard collapsed state is not using the cutout-aware host")

legacy_text = read(LEGACY_CHIP)
if "val next = if (isRtl)" not in legacy_text:
    fail("legacy Dynamic Bar swipe behavior is not RTL semantic")

swipe_demo_text = read(SWIPE_DEMO)
for token in ("LAYOUT_DIRECTION_RTL", "swipeDirection", "arrowTipX"):
    if token not in swipe_demo_text:
        fail(f"Dynamic Bar swipe demo missing RTL behavior: {token}")

keyguard_demo_text = read(KEYGUARD_DEMO)
for token in ("LAYOUT_DIRECTION_RTL", "direction", "Paint.Align.RIGHT"):
    if token not in keyguard_demo_text:
        fail(f"Dynamic Bar keyguard demo missing RTL behavior: {token}")

apply_text = read(APPLY)
for relative in (
    "packages/SystemUI/src/com/android/systemui/axdynamicbar/data/source/SystemIslandManager.kt",
    "packages/SystemUI/src/com/android/systemui/axdynamicbar/domain/AxDynamicBarInteractor.kt",
    "packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/layout/DynamicBarLayoutState.kt",
    "packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/DynamicBarCutoutHost.kt",
    "packages/SystemUI/src/com/android/systemui/statusbar/pipeline/shared/ui/composable/StatusBarRoot.kt",
    "packages/SystemUI/src/com/android/systemui/keyguard/ui/view/layout/sections/AxDynamicBarKeyguardChipSection.kt",
):
    if relative not in apply_text:
        fail(f"apply.sh does not stage {relative}")

print("Dynamic Bar cutout-island validation passed.")

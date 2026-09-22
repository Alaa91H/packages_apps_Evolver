#!/usr/bin/env python3
"""Validate Evolver-owned feature surfaces without staging SystemUI sources."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
AKEY = f"{{{ANDROID_NS}}}key"
ADEFAULT = f"{{{ANDROID_NS}}}defaultValue"
ADEPENDENCY = f"{{{ANDROID_NS}}}dependency"

class ValidationError(RuntimeError):
    pass

def fail(message: str) -> None:
    raise ValidationError(message)

def parse(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"Malformed XML: {path.relative_to(ROOT)}: {exc}")

def collect_resources() -> tuple[set[str], set[str]]:
    strings: set[str] = set()
    arrays: set[str] = set()
    for path in (ROOT / "res/values").glob("*.xml"):
        root = parse(path)
        for child in root:
            name = child.attrib.get("name")
            if not name:
                continue
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "string":
                strings.add(name)
            elif tag in {"array", "string-array", "integer-array"}:
                arrays.add(name)
    return strings, arrays

STRINGS, ARRAYS = collect_resources()

def validate_preference_file(relative: str, *, validate_resources: bool = True) -> set[str]:
    path = ROOT / relative
    root = parse(path)
    keys = [node.attrib[AKEY] for node in root.iter() if AKEY in node.attrib]
    duplicates = sorted(key for key, count in Counter(keys).items() if count > 1)
    if duplicates:
        fail(f"{relative} has duplicate keys: {duplicates}")

    text = path.read_text(encoding="utf-8")
    if validate_resources:
        missing_strings = sorted(set(re.findall(r"@string/([A-Za-z0-9_]+)", text)) - STRINGS)
        missing_arrays = sorted(set(re.findall(r"@array/([A-Za-z0-9_]+)", text)) - ARRAYS)
        if missing_strings:
            fail(f"{relative} references missing Evolver strings: {missing_strings}")
        if missing_arrays:
            fail(f"{relative} references missing Evolver arrays: {missing_arrays}")

    local = set(keys)
    broken_dependencies = sorted(
        node.attrib[ADEPENDENCY]
        for node in root.iter()
        if ADEPENDENCY in node.attrib
        and node.attrib[ADEPENDENCY] not in local
    )
    if broken_dependencies:
        fail(f"{relative} has unresolved dependencies: {broken_dependencies}")
    return local

def validate_cutout_progress() -> None:
    keys = validate_preference_file("res/xml/cutout_progress_settings.xml")
    fragment = (ROOT / "src/org/evolution/settings/fragments/statusbar/CutoutProgressSettingsFragment.kt").read_text(
        encoding="utf-8"
    )
    constants = dict(
        re.findall(r'private const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]+)"', fragment)
    )
    referenced = set(re.findall(r"findPreference\((KEY_[A-Z0-9_]+)\)", fragment))
    unresolved_constants = sorted(referenced - set(constants))
    if unresolved_constants:
        fail(f"Cutout Progress has unresolved preference constants: {unresolved_constants}")
    missing_xml = sorted(constants[name] for name in referenced if constants[name] not in keys)
    if missing_xml:
        fail(f"Cutout Progress fragment references keys absent from XML: {missing_xml}")

def validate_network_traffic() -> None:
    path = ROOT / "res/xml/network_traffic_settings.xml"
    keys = validate_preference_file(str(path.relative_to(ROOT)))
    root = parse(path)
    defaults = {
        node.attrib[AKEY]: node.attrib.get(ADEFAULT)
        for node in root.iter()
        if AKEY in node.attrib
    }
    required_defaults = {
        "network_traffic_enabled": "false",
        "network_traffic_layout_mode": "1",
        "network_traffic_hide_units": "false",
        "network_traffic_speed_text_size": "7",
        "network_traffic_unit_text_size": "6",
        "network_traffic_line_spacing": "95",
        "network_traffic_text_alignment": "2",
    }
    missing = sorted(set(required_defaults) - keys)
    if missing:
        fail(f"Network Traffic is missing Evolver preferences: {missing}")
    for key, expected in required_defaults.items():
        if defaults.get(key) != expected:
            fail(f"Network Traffic default mismatch for {key}: {defaults.get(key)!r}")

def validate_mobile_type() -> None:
    # The top-level status-bar screen also references Settings-owned resources.
    # For this cross-repository boundary check, validate only Evolver-owned keys/logic here.
    keys = validate_preference_file(
        "res/xml/evolution_settings_status_bar.xml",
        validate_resources=False,
    )
    required = {"status_bar_mobile_type_hidden", "status_bar_mobile_type_compact"}
    missing = sorted(required - keys)
    if missing:
        fail(f"Status bar mobile type preferences missing: {missing}")

    java = (ROOT / "src/org/evolution/settings/fragments/statusbar/StatusBar.java").read_text(
        encoding="utf-8"
    )
    for token in (
        'KEY_MOBILE_TYPE_HIDDEN = "status_bar_mobile_type_hidden"',
        'KEY_MOBILE_TYPE_COMPACT = "status_bar_mobile_type_compact"',
        "normalizeMobileTypePreferences(resolver)",
        "mMobileTypeCompact.setEnabled(!hidden)",
    ):
        if token not in java:
            fail(f"StatusBar mobile-type preference handling missing: {token}")

def validate_dynamic_bar() -> None:
    keys = validate_preference_file("res/xml/dynamic_bar.xml")
    required = {
        "ax_dynamic_bar_enabled",
        "ax_dynamic_bar_keyguard_enabled",
        "ax_dynamic_bar_cutout_alignment",
        "ax_dynamic_bar_island_size",
        "ax_dynamic_bar_landscape_mode",
        "ax_dynamic_bar_debug_bounds",
    }
    missing = sorted(required - keys)
    if missing:
        fail(f"Dynamic Bar preferences missing: {missing}")

    kotlin = (ROOT / "src/org/evolution/settings/fragments/statusbar/DynamicBar.kt").read_text(
        encoding="utf-8"
    )
    for key in required:
        if key not in kotlin:
            fail(f"DynamicBar.kt does not own/read expected setting: {key}")

    for demo in (
        ROOT / "src/org/evolution/settings/fragments/statusbar/DynamicBarChipSwipeDemoView.kt",
        ROOT / "src/org/evolution/settings/fragments/statusbar/DynamicBarKeyguardDemoView.kt",
    ):
        text = demo.read_text(encoding="utf-8")
        if "LAYOUT_DIRECTION_RTL" not in text:
            fail(f"Dynamic Bar preview is missing explicit RTL behavior: {demo.relative_to(ROOT)}")

def validate_battery_saver() -> None:
    arrays_path = ROOT / "res/values/battery_saver_arrays.xml"
    root = parse(arrays_path)

    def array_values(name: str) -> list[str]:
        for child in root:
            if child.attrib.get("name") == name:
                return [(item.text or "").strip() for item in child if item.tag == "item"]
        fail(f"Missing Battery Saver array: {name}")

    expected_values = {
        "battery_saver_cpu_limit_values": ["-1", "60", "50", "40", "30", "20", "10"],
        "battery_saver_brightness_reduction_values": ["-1", "10", "20", "30", "40", "50"],
        "battery_saver_screen_timeout_values": ["-1", "15000", "30000"],
    }
    for name, expected in expected_values.items():
        actual = array_values(name)
        if actual != expected:
            fail(f"{name} mismatch: expected {expected}, got {actual}")

    required_strings = {
        "battery_saver_disable_aod_title",
        "battery_saver_cpu_limit_title",
        "battery_saver_brightness_reduction_title",
        "battery_saver_disable_5g_title",
        "battery_saver_force_dark_title",
        "battery_saver_screen_timeout_title",
    }
    missing_strings = sorted(required_strings - STRINGS)
    if missing_strings:
        fail(f"Battery Saver strings missing: {missing_strings}")

    level = (
        ROOT / "src/org/evolution/settings/battery/BatterySaverLevelPreferenceController.java"
    ).read_text(encoding="utf-8")
    for token in (
        'KEY_CPU_LIMIT_PERCENT = "low_power_cpu_limit_percent"',
        'KEY_BRIGHTNESS_REDUCTION = "low_power_brightness_reduction"',
        'KEY_SCREEN_TIMEOUT = "low_power_screen_timeout"',
        'CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq"',
        'CPU_POLICY_PREFIX = "policy"',
        'CPU_SCALING_MAX_FREQ = "scaling_max_freq"',
        'CPUINFO_MAX_FREQ = "cpuinfo_max_freq"',
        "hasUsableCpuFreqPolicy()",
        "isSupportedValue(value)",
        "Settings.Global.putInt(",
        "return 30000;",
    ):
        if token not in level:
            fail(f"BatterySaverLevelPreferenceController missing: {token}")

    for token in (
        "value == 60 || value == 50 || value == 40",
        "value == 30 || value == 20 || value == 10",
        "value == 10 || value == 20 || value == 30",
        "value == 40 || value == 50",
        "value == 15000 || value == 30000",
    ):
        if token not in level:
            fail(f"Battery Saver value validation missing: {token}")

    switches = (
        ROOT / "src/org/evolution/settings/battery/BatterySaverSwitchPreferenceController.java"
    ).read_text(encoding="utf-8")
    for token in (
        'KEY_DISABLE_AOD = "low_power_disable_aod"',
        'KEY_DISABLE_5G = "low_power_disable_5g"',
        'KEY_FORCE_DARK = "low_power_force_dark"',
        "PackageManager.FEATURE_TELEPHONY",
        "policy.getDisableAod()",
        "policy.getEnableNightMode()",
        "Settings.Global.putInt(",
    ):
        if token not in switches:
            fail(f"BatterySaverSwitchPreferenceController missing: {token}")


def validate_repository_boundary() -> None:
    staged = sorted(
        path.relative_to(ROOT).as_posix()
        for path in ROOT.iterdir()
        if path.name.startswith("frameworks_base_")
    )
    if staged:
        fail(
            "Evolver must not stage frameworks/base runtime sources; found: "
            + ", ".join(staged)
        )

def main() -> int:
    try:
        validate_repository_boundary()
        validate_cutout_progress()
        validate_network_traffic()
        validate_mobile_type()
        validate_dynamic_bar()
        validate_battery_saver()
    except (ValidationError, OSError) as exc:
        print(f"EVOLVER FEATURE VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1

    print("Evolver feature-boundary validation passed.")
    print("- frameworks/base runtime staging: none")
    print("- Cutout Progress UI/resource consistency: OK")
    print("- Network Traffic UI/defaults: OK")
    print("- Mobile type preference handling: OK")
    print("- Dynamic Bar UI/settings/RTL previews: OK")
    print("- Battery Saver controllers/resources contract: OK")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Static consistency checks for the Evolver Home double-tap-to-sleep preference."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def require(path: str, needle: str, description: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    checks.append((needle in text, description, path))

def require_count(path: str, needle: str, expected: int, description: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    count = text.count(needle)
    checks.append((count == expected, f"{description} (found {count}, expected {expected})", path))

xml = "res/xml/evolution_settings_miscellaneous.xml"
require_count(
    xml,
    'android:key="home_double_tap_to_sleep"',
    1,
    "Exactly one Home double-tap-to-sleep preference is exposed",
)
require(
    xml,
    "SecureSettingSwitchPreference",
    "The Home gesture preference is backed by Settings.Secure",
)
require(
    xml,
    'android:defaultValue="false"',
    "The Home gesture defaults to disabled",
)
require_count(
    xml,
    'android:key="double_tap_to_sleep"',
    0,
    "The Home preference does not reuse the keyguard double-tap setting",
)

for path, locale in [
    ("res/values/evolution_strings.xml", "English"),
    ("res/values-ar/evolution_strings.xml", "Arabic"),
]:
    require(path, 'name="double_tap_to_sleep_title"', f"{locale} title exists")
    require(path, 'name="double_tap_to_sleep_summary"', f"{locale} summary exists")

failed = [item for item in checks if not item[0]]
for ok, description, path in checks:
    print(f"[{'OK' if ok else 'FAIL'}] {description} :: {path}")

if failed:
    print(f"\n{len(failed)} validation check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} Evolver DT2S checks passed.")

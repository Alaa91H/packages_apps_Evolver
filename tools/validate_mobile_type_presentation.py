#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREF_XML = ROOT / "res/xml/evolution_settings_status_bar.xml"
STATUS_BAR_JAVA = ROOT / "src/org/evolution/settings/fragments/statusbar/StatusBar.java"
SOURCE_STRINGS = ROOT / "res/values/evolution_strings.xml"
ARABIC_STRINGS = ROOT / "res/values-ar/evolution_strings.xml"
PATCH_ROOT = ROOT / "frameworks_base_mobile_type_patch"
APPLY_SCRIPT = PATCH_ROOT / "apply.sh"

REL_VIEW = (
    "packages/SystemUI/src/com/android/systemui/statusbar/pipeline/mobile/ui/view/"
    "ModernStatusBarMobileView.kt"
)
REL_LAYOUT = "packages/SystemUI/res-keyguard/layout/status_bar_mobile_signal_group_inner.xml"
STAGED_VIEW = PATCH_ROOT / REL_VIEW
STAGED_LAYOUT = PATCH_ROOT / REL_LAYOUT

EXPECTED_UPSTREAM = {
    REL_VIEW: "c74da39a327ccf24531e786ca060489a55f4e143",
    REL_LAYOUT: "14e0b5bed493fbbad5669110877910960447a17b",
}
UPSTREAM_BASE = "https://raw.githubusercontent.com/Evolution-X/frameworks_base/cnb/"

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_KEY = f"{{{ANDROID_NS}}}key"
ANDROID_DEFAULT = f"{{{ANDROID_NS}}}defaultValue"
ANDROID_ID = f"{{{ANDROID_NS}}}id"

PREF_DEFAULTS = {
    "status_bar_mobile_type_hidden": "false",
    "status_bar_mobile_type_compact": "false",
}

REQUIRED_STRINGS = {
    "status_bar_mobile_type_hidden_title",
    "status_bar_mobile_type_hidden_summary",
    "status_bar_mobile_type_compact_title",
    "status_bar_mobile_type_compact_summary",
}

REQUIRED_VIEW_TOKENS = (
    'STATUS_BAR_MOBILE_TYPE_HIDDEN = "status_bar_mobile_type_hidden"',
    'STATUS_BAR_MOBILE_TYPE_COMPACT = "status_bar_mobile_type_compact"',
    "Settings.System.getIntForUser",
    "registerContentObserver",
    "UserHandle.USER_CURRENT",
    "UserHandle.USER_ALL",
    "StatusBarLocation.HOME",
    "mobile_type_presentation_container",
    "mobile_signal_container",
    "compactMobileTypeHeight",
    "!hideMobileType",
    "Gravity.START",
    "Gravity.CENTER_HORIZONTAL",
)

FORBIDDEN_DIRECTION_TOKENS = (
    "Gravity.LEFT",
    "Gravity.RIGHT",
    "LAYOUT_DIRECTION_LTR",
    "TEXT_DIRECTION_LTR",
    "layout_marginLeft",
    "layout_marginRight",
    "paddingLeft",
    "paddingRight",
)


class ValidationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


def parse_xml(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"Malformed XML: {path.relative_to(ROOT)}: {exc}")


def run(
    cmd: list[str],
    *,
    cwd: Path | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    if check and proc.returncode != 0:
        detail = "\n".join(
            part for part in (proc.stdout.strip(), proc.stderr.strip()) if part
        )
        fail(f"Command failed ({' '.join(cmd)}):\n{detail}")
    return proc


def parse_string_names(path: Path) -> set[str]:
    root = parse_xml(path)
    return {
        elem.attrib["name"]
        for elem in root.findall("string")
        if "name" in elem.attrib
    }


def validate_evolver() -> None:
    root = parse_xml(PREF_XML)
    keyed = [
        elem
        for elem in root.iter()
        if ANDROID_KEY in elem.attrib
    ]
    keys = [elem.attrib[ANDROID_KEY] for elem in keyed]
    duplicates = sorted(key for key, count in Counter(keys).items() if count > 1)
    if duplicates:
        fail(f"Duplicate status-bar preference keys: {duplicates}")

    by_key = {elem.attrib[ANDROID_KEY]: elem for elem in keyed}
    for key, expected_default in PREF_DEFAULTS.items():
        if key not in by_key:
            fail(f"Missing status-bar preference: {key}")
        actual = by_key[key].attrib.get(ANDROID_DEFAULT)
        if actual != expected_default:
            fail(
                f"Default mismatch for {key}: "
                f"expected {expected_default!r}, got {actual!r}"
            )

    source_names = parse_string_names(SOURCE_STRINGS)
    arabic_names = parse_string_names(ARABIC_STRINGS)
    missing_source = sorted(REQUIRED_STRINGS - source_names)
    missing_arabic = sorted(REQUIRED_STRINGS - arabic_names)
    if missing_source:
        fail(f"Missing source strings: {missing_source}")
    if missing_arabic:
        fail(f"Missing Arabic strings: {missing_arabic}")

    java = STATUS_BAR_JAVA.read_text(encoding="utf-8")
    required_java = (
        'KEY_MOBILE_TYPE_HIDDEN = "status_bar_mobile_type_hidden"',
        'KEY_MOBILE_TYPE_COMPACT = "status_bar_mobile_type_compact"',
        "normalizeMobileTypePreferences(resolver)",
        "mMobileTypeCompact.setEnabled(!hidden)",
        "Settings.System.putIntForUser(resolver, KEY_MOBILE_TYPE_COMPACT, 0",
    )
    missing = [token for token in required_java if token not in java]
    if missing:
        fail(f"StatusBar conflict handling is incomplete: {missing}")


def validate_staged_layout() -> None:
    root = parse_xml(STAGED_LAYOUT)
    ids = [
        elem.attrib[ANDROID_ID]
        for elem in root.iter()
        if ANDROID_ID in elem.attrib
    ]
    required_ids = {
        "@+id/mobile_type_presentation_container",
        "@+id/mobile_type_container",
        "@+id/mobile_type",
        "@+id/mobile_signal_container",
        "@+id/mobile_signal",
        "@+id/mobile_hd",
    }
    missing = sorted(required_ids - set(ids))
    if missing:
        fail(f"Staged mobile layout is missing IDs: {missing}")

    for required_id in ("@+id/mobile_type_container", "@+id/mobile_type"):
        if ids.count(required_id) != 1:
            fail(f"Expected exactly one live pipeline view for {required_id}")


def strip_kotlin(text: str) -> str:
    pattern = re.compile(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        re.S,
    )
    return pattern.sub("", text)


def validate_staged_view() -> None:
    text = STAGED_VIEW.read_text(encoding="utf-8")
    missing = [token for token in REQUIRED_VIEW_TOKENS if token not in text]
    if missing:
        fail(f"Staged mobile view is missing required integration tokens: {missing}")

    forbidden = [token for token in FORBIDDEN_DIRECTION_TOKENS if token in text]
    if forbidden:
        fail(f"RTL-unsafe direction tokens found: {forbidden}")

    if text.count("StatusBarLocation.HOME") < 2:
        fail("Both legacy and Kairos constructors must gate customization to HOME")

    stripped = strip_kotlin(text)
    depth = 0
    for char in stripped:
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth < 0:
                fail("Unbalanced Kotlin braces in staged mobile view")
    if depth != 0:
        fail("Unbalanced Kotlin braces in staged mobile view")

    if re.search(r"^(<<<<<<<|=======|>>>>>>>)", text, re.M):
        fail("Merge-conflict marker found in staged mobile view")


def git_blob_sha(data: bytes) -> str:
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def fetch_upstream(rel: str) -> bytes:
    request = urllib.request.Request(
        UPSTREAM_BASE + rel,
        headers={"User-Agent": "Evolver-mobile-type-validation"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def validate_upstream_and_installer() -> None:
    upstream: dict[str, bytes] = {}
    for rel, expected in EXPECTED_UPSTREAM.items():
        data = fetch_upstream(rel)
        upstream[rel] = data
        actual = git_blob_sha(data)
        if actual != expected:
            fail(
                f"Upstream changed for {rel}: expected {expected}, got {actual}"
            )

    run(["bash", "-n", str(APPLY_SCRIPT)])

    with tempfile.TemporaryDirectory(prefix="evolver-mobile-type-") as tmp:
        target = Path(tmp)
        for rel, data in upstream.items():
            path = target / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

        run(["git", "init", "-q"], cwd=target)
        run(["git", "config", "user.name", "Evolver CI"], cwd=target)
        run(["git", "config", "user.email", "evolver-ci@example.invalid"], cwd=target)
        run(["git", "add", "."], cwd=target)
        run(["git", "commit", "-qm", "Synthetic frameworks/base baseline"], cwd=target)

        first = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if first.returncode != 0:
            detail = "\n".join(
                part for part in (first.stdout.strip(), first.stderr.strip()) if part
            )
            fail(f"Mobile type patch installer failed:\n{detail}")

        second = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if second.returncode != 0:
            detail = "\n".join(
                part for part in (second.stdout.strip(), second.stderr.strip()) if part
            )
            fail(f"Mobile type patch installer is not idempotent:\n{detail}")

        for rel in EXPECTED_UPSTREAM:
            if (target / rel).read_bytes() != (PATCH_ROOT / rel).read_bytes():
                fail(f"Installed file differs from staged source: {rel}")

        guarded_rel = REL_VIEW
        with (target / guarded_rel).open("ab") as handle:
            handle.write(b"\n// deliberate validation mutation\n")
        guarded = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if guarded.returncode == 0:
            fail("Patch installer did not reject a modified target source")


def validate_source_hygiene() -> None:
    paths = (
        PREF_XML,
        STATUS_BAR_JAVA,
        SOURCE_STRINGS,
        ARABIC_STRINGS,
        APPLY_SCRIPT,
        STAGED_VIEW,
        STAGED_LAYOUT,
    )
    for path in paths:
        text = path.read_text(encoding="utf-8")
        bad_lines = [
            index
            for index, line in enumerate(text.splitlines(), 1)
            if line.endswith((" ", "\t"))
        ]
        if bad_lines:
            fail(f"Trailing whitespace in {path.relative_to(ROOT)} lines {bad_lines[:10]}")


def main() -> int:
    try:
        validate_evolver()
        validate_staged_layout()
        validate_staged_view()
        validate_source_hygiene()
        validate_upstream_and_installer()
    except (
        ValidationError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as exc:
        print(f"MOBILE TYPE VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1

    print("Mobile network type presentation validation passed.")
    print("- Evolver preferences/defaults/conflict handling: OK")
    print("- English and Arabic resources: OK")
    print("- Reuses the live RAT view/container: OK")
    print("- HOME-only status-bar scope: OK")
    print("- Compact + hidden presentation structure: OK")
    print("- RTL/LTR relative direction checks: OK")
    print("- Guarded installer + idempotency + mismatch rejection: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

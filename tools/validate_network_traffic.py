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
PREF_XML = ROOT / "res/xml/network_traffic_settings.xml"
VALUES_DIR = ROOT / "res/values"
PATCH_ROOT = ROOT / "frameworks_base_network_traffic_patch"
APPLY_SCRIPT = PATCH_ROOT / "apply.sh"
REL_SYSUI = "packages/SystemUI/src/com/android/systemui/statusbar/NetworkTraffic.java"
STAGED_SYSUI = PATCH_ROOT / REL_SYSUI
EXPECTED_UPSTREAM_SHA = "415011f8046cd15f0a15176acd0df53967789baa"
UPSTREAM_URL = (
    "https://raw.githubusercontent.com/Evolution-X/frameworks_base/cnb/"
    + REL_SYSUI
)

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_KEY = f"{{{ANDROID_NS}}}key"
ANDROID_DEFAULT = f"{{{ANDROID_NS}}}defaultValue"

REQUIRED_DEFAULTS = {
    "network_traffic_layout_mode": "1",
    "network_traffic_hide_units": "false",
    "network_traffic_speed_text_size": "7",
    "network_traffic_unit_text_size": "6",
    "network_traffic_line_spacing": "95",
    "network_traffic_text_alignment": "2",
}

REQUIRED_SYSUI_TOKENS = (
    "LAYOUT_MODE_STACKED",
    "NETWORK_TRAFFIC_LAYOUT_MODE",
    "NETWORK_TRAFFIC_HIDE_UNITS",
    "NETWORK_TRAFFIC_SPEED_TEXT_SIZE",
    "NETWORK_TRAFFIC_UNIT_TEXT_SIZE",
    "NETWORK_TRAFFIC_LINE_SPACING",
    "NETWORK_TRAFFIC_TEXT_ALIGNMENT",
    "formatOutput(mTxBytes, true)",
    "formatOutput(mRxBytes, true)",
    "setCompoundDrawablesRelativeWithIntrinsicBounds",
    "View.LAYOUT_DIRECTION_LOCALE",
    "View.TEXT_DIRECTION_FIRST_STRONG_LTR",
    "View.TEXT_ALIGNMENT_VIEW_START",
    "View.TEXT_ALIGNMENT_VIEW_END",
    "Gravity.START",
    "Gravity.END",
)


class ValidationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


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


def parse_xml(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"Malformed XML: {path.relative_to(ROOT)}: {exc}")


def collect_resources() -> tuple[set[str], set[str]]:
    strings: set[str] = set()
    arrays: set[str] = set()
    for path in sorted(VALUES_DIR.glob("*.xml")):
        root = parse_xml(path)
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


def validate_evolver_resources() -> None:
    root = parse_xml(PREF_XML)
    keys = [
        elem.attrib[ANDROID_KEY]
        for elem in root.iter()
        if ANDROID_KEY in elem.attrib
    ]
    duplicates = sorted(key for key, count in Counter(keys).items() if count > 1)
    if duplicates:
        fail(f"Duplicate Network Traffic preference keys: {duplicates}")

    network_keys = {key for key in keys if key.startswith("network_traffic_")}
    missing = sorted(set(REQUIRED_DEFAULTS) - network_keys)
    if missing:
        fail(f"Missing new Network Traffic preferences: {missing}")

    defaults = {
        elem.attrib[ANDROID_KEY]: elem.attrib.get(ANDROID_DEFAULT)
        for elem in root.iter()
        if ANDROID_KEY in elem.attrib
    }
    for key, expected in REQUIRED_DEFAULTS.items():
        actual = defaults.get(key)
        if actual != expected:
            fail(f"Default mismatch for {key}: expected {expected!r}, got {actual!r}")

    strings, arrays = collect_resources()
    xml_text = PREF_XML.read_text(encoding="utf-8")
    string_refs = set(re.findall(r"@string/([A-Za-z0-9_]+)", xml_text))
    array_refs = set(re.findall(r"@array/([A-Za-z0-9_]+)", xml_text))
    missing_strings = sorted(string_refs - strings)
    missing_arrays = sorted(array_refs - arrays)
    if missing_strings:
        fail(f"Missing string resources referenced by Network Traffic: {missing_strings}")
    if missing_arrays:
        fail(f"Missing array resources referenced by Network Traffic: {missing_arrays}")

    staged = STAGED_SYSUI.read_text(encoding="utf-8")
    missing_key_tokens = sorted(
        key for key in network_keys if key.upper() not in staged
    )
    if missing_key_tokens:
        fail(
            "Evolver Network Traffic keys missing from staged SystemUI constants: "
            + str(missing_key_tokens)
        )


def strip_code(text: str) -> str:
    # Enough for brace validation: remove comments, strings and character literals.
    pattern = re.compile(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        re.S,
    )
    return pattern.sub("", text)


def validate_java_structure() -> None:
    text = STAGED_SYSUI.read_text(encoding="utf-8")

    missing = [token for token in REQUIRED_SYSUI_TOKENS if token not in text]
    if missing:
        fail(f"Staged SystemUI is missing required integration tokens: {missing}")

    tx = text.index("formatOutput(mTxBytes, true)")
    rx = text.index("formatOutput(mRxBytes, true)")
    if tx >= rx:
        fail("Stacked line ordering must remain upload first, download second")

    if "setCompoundDrawables(" in text:
        fail("Absolute compound-drawable API found; use relative RTL-safe APIs")
    for token in ("Gravity.LEFT", "Gravity.RIGHT", "LAYOUT_DIRECTION_LTR"):
        if token in text:
            fail(f"RTL-unsafe absolute direction token found: {token}")

    stripped = strip_code(text)
    depth = 0
    for char in stripped:
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth < 0:
                fail("Unbalanced Java braces in staged NetworkTraffic.java")
    if depth != 0:
        fail("Unbalanced Java braces in staged NetworkTraffic.java")

    if re.search(r"^(<<<<<<<|=======|>>>>>>>)", text, re.M):
        fail("Merge-conflict marker found in staged NetworkTraffic.java")


def git_blob_sha(data: bytes) -> str:
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def fetch_upstream() -> bytes:
    request = urllib.request.Request(
        UPSTREAM_URL,
        headers={"User-Agent": "Evolver-network-traffic-validation"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def validate_upstream_and_installer() -> None:
    upstream = fetch_upstream()
    actual_sha = git_blob_sha(upstream)
    if actual_sha != EXPECTED_UPSTREAM_SHA:
        fail(
            "Upstream NetworkTraffic.java changed: "
            f"expected {EXPECTED_UPSTREAM_SHA}, got {actual_sha}"
        )

    run(["bash", "-n", str(APPLY_SCRIPT)])

    with tempfile.TemporaryDirectory(prefix="evolver-network-traffic-") as tmp:
        target = Path(tmp)
        source = target / REL_SYSUI
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(upstream)

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
            fail(f"Network Traffic patch installer failed:\n{detail}")

        second = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if second.returncode != 0:
            detail = "\n".join(
                part for part in (second.stdout.strip(), second.stderr.strip()) if part
            )
            fail(f"Network Traffic patch installer is not idempotent:\n{detail}")

        if source.read_bytes() != STAGED_SYSUI.read_bytes():
            fail("Installed NetworkTraffic.java differs from staged source")

        with source.open("ab") as handle:
            handle.write(b"\n// deliberate validation mutation\n")
        guarded = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if guarded.returncode == 0:
            fail("Patch installer did not reject a modified target source")


def validate_source_hygiene() -> None:
    # Limit whitespace enforcement to feature-owned files. The larger resource files may
    # contain unrelated pre-existing formatting that this integration must not rewrite.
    for path in (
        PREF_XML,
        APPLY_SCRIPT,
        STAGED_SYSUI,
        ROOT / "tools/validate_network_traffic.py",
    ):
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
        validate_evolver_resources()
        validate_java_structure()
        validate_source_hygiene()
        validate_upstream_and_installer()
    except (
        ValidationError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
    ) as exc:
        print(f"NETWORK TRAFFIC VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1

    print("Stacked Network Traffic validation passed.")
    print("- Evolver resources/defaults/key parity: OK")
    print("- Upload-above-download presentation: OK")
    print("- Unit hiding and appearance controls: OK")
    print("- RTL/LTR relative alignment and drawables: OK")
    print("- Java structure/source hygiene: OK")
    print("- frameworks/base baseline SHA: OK")
    print("- Guarded installer + idempotency + mismatch rejection: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

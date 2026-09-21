# Stacked Network Traffic — SystemUI integration

This directory contains the `frameworks/base` half of Evolver's enhanced status-bar network
traffic monitor. Evolver owns the preferences; SystemUI owns the live rendering.

## Baseline

The staged renderer targets `Evolution-X/frameworks_base:cnb` as observed at commit
`ed06b1ac4d92177f5c5b311290a12e38659c2411`.

The guarded source file baseline is:

```
packages/SystemUI/src/com/android/systemui/statusbar/NetworkTraffic.java
415011f8046cd15f0a15176acd0df53967789baa
```

The installer accepts either that exact upstream blob or this patch's already-applied blob. Any
other local/upstream state aborts unless `--force` is explicitly supplied after manual review.

## What changes

When **Display mode** is set to upload + download, the new **Stacked** presentation shows:

```
upload speed
download speed
```

Upload is always the first visual line and download is always the second visual line in both LTR
and RTL locales. The older dominant-speed behavior remains available as **Adaptive**.

Additional Evolver controls are wired directly into SystemUI:

- hide/show rate units such as `kB/s`, `MB/s`, `kb/s`;
- speed text size;
- unit text size;
- line spacing;
- relative Start / Center / End alignment;
- existing bit/byte unit selection, auto-hide, threshold, refresh interval and arrow visibility.

The renderer keeps numeric/unit tokens internally LTR (their natural representation), while the
view layout and Start/End gravity follow the active locale. Compound drawables are relative, so the
traffic arrow also mirrors correctly for RTL layouts.

## Apply

From the root of the Evolver checkout:

```bash
bash frameworks_base_network_traffic_patch/apply.sh /path/to/android/frameworks/base
```

Do not use `--force` unless the upstream mismatch has been manually reviewed.

## Defaults

- presentation: **Stacked**;
- speed text: **7sp**;
- unit text: **6sp**;
- line spacing: **95%**;
- alignment: **End**;
- units remain visible unless explicitly hidden.

The existing upload-only and download-only modes are preserved.

## Validation

`tools/validate_network_traffic.py` checks:

- preference key uniqueness and expected defaults;
- referenced string/array resources;
- Evolver ↔ staged SystemUI key parity;
- upload-before-download stacked ordering;
- unit-hiding and appearance tuning hooks;
- relative RTL/LTR APIs for alignment and drawables;
- Java brace balance and conflict markers;
- current upstream blob hash;
- guarded installer success, idempotency and mismatch rejection.

A full SystemUI build and on-device validation are still recommended before merging into a ROM
build. Device tests should cover LTR/RTL locale switching, font scale, hidden units, hidden arrows,
upload-only/download-only modes, auto-hide, high traffic rates and SystemUI restart.

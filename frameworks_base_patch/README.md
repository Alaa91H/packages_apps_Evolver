# Cutout Progress Pro — SystemUI integration

This directory contains the SystemUI half of the Cutout Progress Pro work developed together with
the settings UI in this `packages_apps_Evolver` branch.

## Baseline

The staged files were produced against `Evolution-X/frameworks_base` branch `cnb` while its head
was:

```
d9b63b2f4753a38b3f4e63378f5267e8e7ed9f29
```

The installer verifies the original Git blob of every changed Cutout Progress file before copying
anything, so later upstream changes are not silently overwritten.

## Apply

From the root of this Evolver checkout:

```bash
bash frameworks_base_patch/apply.sh /path/to/android/frameworks/base
```

Do not use `--force` unless every upstream mismatch has been reviewed manually.

## What is implemented

- Correct camera-cutout selection instead of blindly using the aggregate DisplayCutout path.
- No synthetic/fake cutout on devices that report no physical cutout.
- Circle geometry uses the minor cutout axis, fixing oversized rings on rectangular safe-area paths.
- Calibrated default geometry: pill mode, X 1.050, Y 0.600, X offset 0, Y offset 1.5dp.
- Download progress no longer disappears after 10 seconds while an active download still exists.
- Safer download tracking:
  - unique StatusBarNotification key;
  - 64-bit progress math;
  - stale-entry cleanup;
  - indeterminate transitions do not fake completion;
  - unrelated non-ongoing/non-progress notifications are ignored;
  - completion/error animation is conservative.
- Optional source presentation policies:
  - **Primary ring**
  - **Independent outer ring**
  - **Disabled**
- Download and music can coexist as independent automatically stacked rings.
- User-selectable primary-ring priority when both download and music request the main ring.
- Configurable automatic multi-ring spacing.
- Charging/battery use the primary ring when it is free, so three visual sources can coexist without
  manually tuning offsets.
- Optional animated music waveform outside the music ring.
- Waveform follows the resolved music-ring color, including dynamic artwork/palette colors.
- Music artwork color extraction is race-safe and does not recycle Bitmaps owned by media apps.
- Music palette executor is shut down when the feature stops.
- Music progress updates at ~30 Hz instead of invalidating the full overlay at 60/120 Hz.
- Music AOD preference is actually honored.
- Music controller is stopped immediately when disabled.
- Battery receiver is registered only when needed and immediately consumes sticky battery state.
- Unrelated Settings.Secure changes no longer reconfigure the feature.

## Rendering policy

A source can request the primary ring or an independent ring. Independent rings are assigned lanes
automatically and expand outward from the calibrated camera geometry. When both download and music
are independent, the priority preference also determines their inner/outer ordering.

The music waveform is a visual ambient waveform, not a capture of device audio. It intentionally
does not use `Visualizer` or microphone/global-audio capture, avoiding extra recording permissions,
privacy concerns, and continuous audio-analysis overhead inside SystemUI.

## Files

The directory layout under `frameworks_base_patch/` mirrors `frameworks/base/`. The installer
copies only the seven Cutout Progress files staged here.

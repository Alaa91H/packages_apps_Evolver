# Mobile network type presentation — SystemUI integration

This directory contains the `frameworks/base` half of Evolver's status-bar controls for the
mobile network type indicator (LTE, 4G, 5G and similar RAT icons).

## Baseline

The staged sources target `Evolution-X/frameworks_base:cnb`.

| File | Guarded upstream blob |
| --- | --- |
| `packages/SystemUI/src/com/android/systemui/statusbar/pipeline/mobile/ui/view/ModernStatusBarMobileView.kt` | `c74da39a327ccf24531e786ca060489a55f4e143` |
| `packages/SystemUI/res-keyguard/layout/status_bar_mobile_signal_group_inner.xml` | `14e0b5bed493fbbad5669110877910960447a17b` |

The installer accepts either each exact upstream blob or this patch's already-applied blob. Any
third state aborts unless `--force` is explicitly supplied after manual review.

## Evolver options

Under **Status bar → Icons**:

- **Hide mobile network type** removes the LTE/4G/5G indicator while preserving the signal bars.
- **Compact network type with signal** reuses the live RAT indicator and places it above the mobile
  signal bars at half of its normal height, reducing horizontal status-bar usage.

The options are mutually exclusive in Evolver. SystemUI also gives **Hide** precedence if settings
are externally placed into a conflicting state.

## SystemUI design

The implementation deliberately reuses `mobile_type_container` and `mobile_type` rather than
creating a second icon. That means the existing mobile pipeline remains responsible for:

- live LTE/4G/5G/3G transitions;
- tint and contrast changes;
- optional RAT background layers;
- per-subscription / dual-SIM state;
- both legacy and Kairos mobile icon binders.

A lightweight presentation wrapper can be re-parented between the normal horizontal mobile group
and the signal icon's frame. In compact mode, the RAT indicator occupies the top part of that frame
while the signal bars sit at the bottom. This saves horizontal space without changing the data
pipeline.

The customization is enabled only for `StatusBarLocation.HOME`, so Quick Settings, carrier shade
and lockscreen locations keep their stock presentation.

RTL/LTR behavior uses relative margins and `Gravity.START`; the compact RAT itself is horizontally
centered, so its geometry is direction-neutral.

## Apply

From the Evolver checkout:

```bash
bash frameworks_base_mobile_type_patch/apply.sh /path/to/android/frameworks/base
```

Do not use `--force` unless the upstream mismatch has been manually reviewed.

## Validation

`tools/validate_mobile_type_presentation.py` validates Evolver preferences, English/Arabic
resources, conflict handling, staged SystemUI structure, RTL-safe direction usage, guarded upstream
blob SHAs, installer idempotency and mismatch rejection.

A full SystemUI build and device test are still the final integration gate. Device coverage should
include LTE/4G/5G transitions, no-service states, dual SIM, VoLTE/VoNR HD icon, roaming, dark/light
status-bar tint, font/display scaling, LTR/RTL locale switching, and toggling both options without a
SystemUI restart.

/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.cutoutprogress;

import java.util.Objects;

/**
 * Immutable runtime dependency policy for Cutout Progress.
 *
 * <p>This translates user settings into the minimal set of SystemUI observers, receivers and
 * trackers that must be alive. Keeping the policy separate from {@link CutoutProgressController}
 * makes lifecycle decisions deterministic and prevents visual-only preference changes from
 * accidentally enabling unrelated background work.</p>
 */
final class CutoutProgressRuntimePolicy {

    static final CutoutProgressRuntimePolicy DISABLED = new CutoutProgressRuntimePolicy(
            false, false, false, false, false, false, false, false, false);

    final boolean enabled;
    final boolean downloadTracking;
    final boolean timerTracking;
    final boolean notificationAuroraTracking;
    final boolean callNotificationTracking;
    final boolean batteryTracking;
    final boolean musicTracking;
    final boolean callStateTracking;
    final boolean recordingTracking;

    private CutoutProgressRuntimePolicy(
            boolean enabled,
            boolean downloadTracking,
            boolean timerTracking,
            boolean notificationAuroraTracking,
            boolean callNotificationTracking,
            boolean batteryTracking,
            boolean musicTracking,
            boolean callStateTracking,
            boolean recordingTracking) {
        this.enabled = enabled;
        this.downloadTracking = downloadTracking;
        this.timerTracking = timerTracking;
        this.notificationAuroraTracking = notificationAuroraTracking;
        this.callNotificationTracking = callNotificationTracking;
        this.batteryTracking = batteryTracking;
        this.musicTracking = musicTracking;
        this.callStateTracking = callStateTracking;
        this.recordingTracking = recordingTracking;
    }

    static CutoutProgressRuntimePolicy from(CutoutProgressSettings settings) {
        if (!settings.isEnabled()) return DISABLED;

        final boolean auroraEnabled = settings.isAuroraEnabled();
        final boolean downloadTracking = settings.getDownloadPresentation()
                != CutoutProgressSettings.PRESENTATION_DISABLED;
        final boolean timerTracking = settings.isTimerEnabled()
                && settings.getTimerPresentation()
                != CutoutProgressSettings.PRESENTATION_DISABLED;
        final boolean notificationAuroraTracking = auroraEnabled
                && settings.isAuroraNotificationsEnabled();
        final boolean callNotificationTracking = auroraEnabled
                && settings.isAuroraCallsEnabled();
        final boolean batteryTracking = settings.isChargingRingEnabled()
                || settings.isBatteryIndicatorEnabled();
        final boolean musicVisible = settings.isMusicRingEnabled()
                && settings.getMusicPresentation()
                != CutoutProgressSettings.PRESENTATION_DISABLED;
        final boolean musicTracking = musicVisible
                || (auroraEnabled && settings.isAuroraMusicEnabled());

        return new CutoutProgressRuntimePolicy(
                true,
                downloadTracking,
                timerTracking,
                notificationAuroraTracking,
                callNotificationTracking,
                batteryTracking,
                musicTracking,
                auroraEnabled && settings.isAuroraCallsEnabled(),
                auroraEnabled && settings.isAuroraRecordingEnabled());
    }

    boolean needsNotificationPipeline() {
        return downloadTracking
                || timerTracking
                || notificationAuroraTracking
                || callNotificationTracking;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CutoutProgressRuntimePolicy)) return false;
        CutoutProgressRuntimePolicy other = (CutoutProgressRuntimePolicy) obj;
        return enabled == other.enabled
                && downloadTracking == other.downloadTracking
                && timerTracking == other.timerTracking
                && notificationAuroraTracking == other.notificationAuroraTracking
                && callNotificationTracking == other.callNotificationTracking
                && batteryTracking == other.batteryTracking
                && musicTracking == other.musicTracking
                && callStateTracking == other.callStateTracking
                && recordingTracking == other.recordingTracking;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                enabled,
                downloadTracking,
                timerTracking,
                notificationAuroraTracking,
                callNotificationTracking,
                batteryTracking,
                musicTracking,
                callStateTracking,
                recordingTracking);
    }
}

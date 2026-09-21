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

package com.android.systemui.cutoutprogress.ring;

import com.android.systemui.cutoutprogress.CutoutProgressSettings;

/**
 * Pure routing policy for assigning active Cutout Progress sources to the primary ring and
 * independent outward lanes.
 *
 * <p>The router deliberately owns no View state and performs no allocation while resolving a
 * frame. This keeps routing independently testable while allowing {@link CutoutRingView} to remain
 * responsible only for rendering the resolved layout.</p>
 */
final class RingRouter {

    static final int SOURCE_NONE = 0;
    static final int SOURCE_DOWNLOAD = 1;
    static final int SOURCE_MUSIC = 2;
    static final int SOURCE_CHARGING = 3;
    static final int SOURCE_BATTERY = 4;
    static final int SOURCE_TIMER = 5;

    private RingRouter() {}

    static final class Result {
        int primarySource = SOURCE_NONE;
        int independentCount = 0;
        private final int[] mIndependentSources = new int[3];

        void reset() {
            primarySource = SOURCE_NONE;
            independentCount = 0;
        }

        void addIndependent(int source) {
            if (independentCount >= mIndependentSources.length) return;
            mIndependentSources[independentCount++] = source;
        }

        int independentSourceAt(int index) {
            if (index < 0 || index >= independentCount) return SOURCE_NONE;
            return mIndependentSources[index];
        }

        boolean hasRings() {
            return primarySource != SOURCE_NONE || independentCount > 0;
        }
    }

    static void resolve(
            Result out,
            boolean preview,
            boolean downloadActive,
            boolean musicActive,
            boolean timerActive,
            boolean forceDownload,
            int downloadPresentation,
            int musicPresentation,
            int timerPresentation,
            int primaryPriority,
            boolean chargingActive,
            boolean chargingRingEnabled,
            boolean batteryIndicatorActive) {
        out.reset();

        final boolean downloadPrimary = preview || (downloadActive
                && downloadPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY);
        final boolean musicPrimary = musicActive
                && musicPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY;
        final boolean timerPrimary = timerActive
                && timerPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY;

        if (forceDownload && downloadPrimary) {
            out.primarySource = SOURCE_DOWNLOAD;
        } else {
            final int preferred = priorityToSource(primaryPriority);
            if (preferred == SOURCE_DOWNLOAD && downloadPrimary) {
                out.primarySource = SOURCE_DOWNLOAD;
            } else if (preferred == SOURCE_MUSIC && musicPrimary) {
                out.primarySource = SOURCE_MUSIC;
            } else if (preferred == SOURCE_TIMER && timerPrimary) {
                out.primarySource = SOURCE_TIMER;
            } else if (downloadPrimary) {
                out.primarySource = SOURCE_DOWNLOAD;
            } else if (musicPrimary) {
                out.primarySource = SOURCE_MUSIC;
            } else if (timerPrimary) {
                out.primarySource = SOURCE_TIMER;
            }
        }

        if (out.primarySource == SOURCE_NONE && chargingActive && chargingRingEnabled) {
            out.primarySource = SOURCE_CHARGING;
        } else if (out.primarySource == SOURCE_NONE
                && batteryIndicatorActive && !chargingActive) {
            out.primarySource = SOURCE_BATTERY;
        }

        final boolean downloadIndependent = !preview && downloadActive
                && downloadPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        final boolean musicIndependent = musicActive
                && musicPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        final boolean timerIndependent = timerActive
                && timerPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;

        final int preferred = priorityToSource(primaryPriority);
        addPreferredIndependent(out, preferred,
                downloadIndependent, musicIndependent, timerIndependent);

        if (preferred != SOURCE_DOWNLOAD && downloadIndependent) {
            out.addIndependent(SOURCE_DOWNLOAD);
        }
        if (preferred != SOURCE_MUSIC && musicIndependent) {
            out.addIndependent(SOURCE_MUSIC);
        }
        if (preferred != SOURCE_TIMER && timerIndependent) {
            out.addIndependent(SOURCE_TIMER);
        }
    }

    static float nextLaneOffsetDp(
            float previousOffsetDp,
            float previousStrokeDp,
            float currentStrokeDp,
            float configuredSpacingDp) {
        final float nonOverlapStep = (Math.max(0f, previousStrokeDp)
                + Math.max(0f, currentStrokeDp)) * 0.5f + 0.75f;
        return previousOffsetDp + Math.max(configuredSpacingDp, nonOverlapStep);
    }

    static int priorityToSource(int primaryPriority) {
        switch (primaryPriority) {
            case CutoutProgressSettings.PRIMARY_PRIORITY_MUSIC:
                return SOURCE_MUSIC;
            case CutoutProgressSettings.PRIMARY_PRIORITY_TIMER:
                return SOURCE_TIMER;
            default:
                return SOURCE_DOWNLOAD;
        }
    }

    private static void addPreferredIndependent(
            Result out,
            int preferred,
            boolean downloadIndependent,
            boolean musicIndependent,
            boolean timerIndependent) {
        if (preferred == SOURCE_DOWNLOAD && downloadIndependent) {
            out.addIndependent(SOURCE_DOWNLOAD);
        } else if (preferred == SOURCE_MUSIC && musicIndependent) {
            out.addIndependent(SOURCE_MUSIC);
        } else if (preferred == SOURCE_TIMER && timerIndependent) {
            out.addIndependent(SOURCE_TIMER);
        }
    }
}

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

import android.app.Notification;
import android.os.Bundle;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks determinate, ongoing progress notifications without treating every progress-style
 * notification transition as a completed download.
 *
 * The tracker intentionally keeps the last known determinate percentage when an already tracked
 * transfer temporarily becomes indeterminate. This avoids false completion animations and ring
 * flicker during network/app state transitions.
 */
public final class DownloadStateTracker {

    private static final long STALE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int COMPLETE_THRESHOLD_PCT = 99;
    private static final int APP_CANCEL_COMPLETE_THRESHOLD_PCT = 90;

    // NotificationListenerService cancellation reasons. Kept local to avoid depending on listener
    // implementation details from SystemUI.
    private static final int REASON_ERROR = 4;
    private static final int REASON_APP_CANCEL = 8;
    private static final int REASON_APP_CANCEL_ALL = 9;

    private static final class DownloadSnapshot {
        String label;
        int progress;
        long updatedAt;

        DownloadSnapshot(String label, int progress, long updatedAt) {
            this.label = label;
            this.progress = progress;
            this.updatedAt = updatedAt;
        }
    }

    private final ConcurrentHashMap<String, DownloadSnapshot> mActive =
            new ConcurrentHashMap<>();

    public interface IntCallback { void onValue(int value); }
    public interface StringCallback { void onValue(String value); }

    private IntCallback mOnProgress;
    private Runnable mOnComplete;
    private Runnable mOnError;
    private IntCallback mOnCountChanged;
    private StringCallback mOnLabelChanged;

    public void setOnProgress(IntCallback cb) { mOnProgress = cb; }
    public void setOnComplete(Runnable cb) { mOnComplete = cb; }
    public void setOnError(Runnable cb) { mOnError = cb; }
    public void setOnCountChanged(IntCallback cb) { mOnCountChanged = cb; }
    public void setOnLabelChanged(StringCallback cb) { mOnLabelChanged = cb; }

    public void onNotificationChanged(NotificationEntry entry) {
        if (entry == null || entry.getSbn() == null) return;

        final Notification notification = entry.getSbn().getNotification();
        if (notification == null) return;

        final Bundle extras = notification.extras;
        if (extras == null) return;

        final long now = System.currentTimeMillis();
        pruneStale(now);

        final String id = entryKey(entry);
        final DownloadSnapshot existing = mActive.get(id);

        final int rawProgress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
        final int rawMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
        final boolean indeterminate =
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);

        final boolean hasProgressPayload =
                extras.containsKey(Notification.EXTRA_PROGRESS)
                        && extras.containsKey(Notification.EXTRA_PROGRESS_MAX);
        final boolean ongoing =
                (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        final boolean progressCategory =
                Notification.CATEGORY_PROGRESS.equals(notification.category);

        final boolean determinate = hasProgressPayload && !indeterminate
                && rawProgress >= 0 && rawMax > 0;
        final int pct = determinate
                ? clamp((int) (((long) rawProgress * 100L) / rawMax), 0, 100)
                : -1;

        // A completion update may clear FLAG_ONGOING before SystemUI receives it. Complete only
        // a transfer that we actually tracked; a newly posted 100% notification should not flash.
        if (pct >= 100) {
            if (existing != null) {
                mActive.remove(id);
                notifyCountChanged();
                publishAggregated();
                fireComplete();
            }
            return;
        }

        // Ignore unrelated transient progress payloads. If a transfer that we were already
        // tracking changes shape, remove it quietly rather than pretending it completed.
        if (!hasProgressPayload || (!ongoing && !progressCategory)) {
            removeQuietly(id);
            return;
        }

        // A tracked download can temporarily switch to indeterminate progress while reconnecting
        // or preparing the next stage. Keep its last known percentage alive instead of firing a
        // completion animation.
        if (!determinate) {
            if (existing != null) {
                existing.updatedAt = now;
                String label = title(extras);
                if (label != null) existing.label = label;
                publishAggregated();
            }
            return;
        }

        final String label = title(extras);

        if (existing == null) {
            mActive.put(id, new DownloadSnapshot(label, pct, now));
            notifyCountChanged();
        } else {
            existing.progress = pct;
            existing.updatedAt = now;
            if (label != null && !Objects.equals(label, existing.label)) {
                existing.label = label;
            }
        }

        publishAggregated();
    }

    public void onNotificationRemoved(NotificationEntry entry, int reason) {
        if (entry == null || entry.getSbn() == null) return;

        final DownloadSnapshot snap = mActive.remove(entryKey(entry));
        if (snap == null) return;

        notifyCountChanged();
        publishAggregated();

        if (snap.progress >= COMPLETE_THRESHOLD_PCT
                || ((reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL)
                        && snap.progress >= APP_CANCEL_COMPLETE_THRESHOLD_PCT)) {
            fireComplete();
        } else if (reason == REASON_ERROR) {
            fireError();
        }
    }

    public void reset() {
        mActive.clear();
        notifyCountChanged();
        fire(mOnProgress, 0);
        fire(mOnLabelChanged, null);
    }

    public int getActiveCount() {
        return mActive.size();
    }

    private void removeQuietly(String id) {
        if (mActive.remove(id) != null) {
            notifyCountChanged();
            publishAggregated();
        }
    }

    private void pruneStale(long now) {
        boolean changed = false;
        for (Map.Entry<String, DownloadSnapshot> e : mActive.entrySet()) {
            DownloadSnapshot snap = e.getValue();
            if (now - snap.updatedAt > STALE_TIMEOUT_MS
                    && mActive.remove(e.getKey(), snap)) {
                changed = true;
            }
        }
        if (changed) {
            notifyCountChanged();
            publishAggregated();
        }
    }

    private void publishAggregated() {
        int avg = 0;
        if (!mActive.isEmpty()) {
            long sum = 0;
            for (DownloadSnapshot s : mActive.values()) {
                sum += s.progress;
            }
            avg = (int) (sum / mActive.size());
        }
        fire(mOnProgress, avg);
        publishBestLabel();
    }

    private void publishBestLabel() {
        DownloadSnapshot best = null;
        for (DownloadSnapshot s : mActive.values()) {
            if (best == null || s.progress > best.progress) best = s;
        }

        String label = null;
        if (best != null && best.label != null
                && !best.label.toLowerCase().contains("untitled")) {
            label = best.label;
        }
        fire(mOnLabelChanged, label);
    }

    private String entryKey(NotificationEntry entry) {
        // StatusBarNotification#getKey includes user/package/id/tag and avoids collisions that can
        // occur when only package + numeric id are used.
        return entry.getSbn().getKey();
    }

    private static String title(Bundle extras) {
        CharSequence value = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (value == null) return null;
        String title = value.toString().trim();
        return title.isEmpty() ? null : title;
    }

    private void notifyCountChanged() {
        fire(mOnCountChanged, mActive.size());
    }

    private void fireComplete() {
        if (mOnComplete != null) mOnComplete.run();
    }

    private void fireError() {
        if (mOnError != null) mOnError.run();
    }

    private void fire(IntCallback cb, int value) {
        if (cb != null) cb.onValue(value);
    }

    private void fire(StringCallback cb, String value) {
        if (cb != null) cb.onValue(value);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}

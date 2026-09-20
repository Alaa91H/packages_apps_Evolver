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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.cutoutprogress.ring.CutoutRingView;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

@SysUISingleton
public class CutoutProgressController implements CoreStartable {

    private final Context mContext;
    private final NotifPipeline mPipeline;
    private final Handler mMainHandler;
    private final UserTracker mUserTracker;
    private final ConfigurationController mConfigurationController;

    private final CutoutProgressSettings mSettings;
    private final DownloadStateTracker mTracker;
    private CutoutRingView mRingView;

    private MusicRingController mMusicController;

    private boolean mOverlayAttached = false;
    private boolean mBatteryReceiverRegistered = false;
    private NotifCollectionListener mNotifListener;
    private boolean mDownloadTrackingEnabled = false;
    private boolean mTimerTrackingEnabled = false;
    private boolean mNotificationAuroraTrackingEnabled = false;

    private boolean mCallReceiverRegistered = false;
    private AudioManager mAudioManager;
    private boolean mRecordingCallbackRegistered = false;

    private String mTimerKey;
    private long mTimerEndElapsedMs = 0L;
    private long mTimerTotalMs = 0L;
    private boolean mTimerRunning = false;

    private static final class CountdownInfo {
        final long endElapsedMs;
        final boolean running;

        CountdownInfo(long endElapsedMs, boolean running) {
            this.endElapsedMs = endElapsedMs;
            this.running = running;
        }
    }

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onThemeChanged() {
            refreshThemeDependentColors();
        }

        @Override
        public void onUiModeChanged() {
            refreshThemeDependentColors();
        }
    };

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            // Tear down state from the previous user before reading the new user's Secure settings.
            disableFeature();
            mSettings.setUserId(newUser);
            onSettingsChanged();
        }
    };

    private final BroadcastReceiver mCallStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            seedCallState();
        }
    };

    private final AudioManager.AudioRecordingCallback mRecordingCallback =
            new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            boolean active = hasUserVisibleRecording(configs);
            runOnMain(() -> mRingView.setAuroraRecordingActive(active));
        }
    };

    private final Runnable mTimerTick = this::updateTimerTick;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int rawPct = scale > 0 ? level * 100 / scale : 0;
            final int pct = Math.max(0, Math.min(100, rawPct));

            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            if (!mSettings.isEnabled()) {
                runOnMain(() -> {
                    mRingView.setChargingState(false, 0);
                    mRingView.setBatteryIndicatorState(false, 0);
                });
                return;
            }

            boolean chargingRingOn = mSettings.isChargingRingEnabled();
            boolean batteryIndOn = mSettings.isBatteryIndicatorEnabled();
            boolean pulseEnabled = mSettings.isChargingPulseEnabled();

            runOnMain(() -> {
                mRingView.setChargingPulseEnabled(pulseEnabled);
                if (charging) {
                    mRingView.setBatteryIndicatorState(false, 0);
                    mRingView.setChargingState(chargingRingOn, pct);
                } else {
                    mRingView.setChargingState(false, 0);
                    mRingView.setBatteryIndicatorState(batteryIndOn, pct);
                }
            });
        }
    };

    @Inject
    public CutoutProgressController(
            Context context,
            NotifPipeline notifPipeline,
            @Main Handler mainHandler,
            UserTracker userTracker,
            ConfigurationController configurationController) {
        mContext = context;
        mPipeline = notifPipeline;
        mMainHandler = mainHandler;
        mUserTracker = userTracker;
        mConfigurationController = configurationController;
        mSettings = new CutoutProgressSettings(
                context.getContentResolver(), mainHandler, userTracker.getUserId());
        mTracker = new DownloadStateTracker();
    }

    @Override
    public void start() {
        mRingView = new CutoutRingView(mContext);
        mRingView.applySettings(mSettings);
        bindTrackerToView();

        mMusicController = new MusicRingController(mContext, mMainHandler, mRingView);
        mMusicController.applySettings(mSettings);

        mConfigurationController.addCallback(mConfigurationListener);
        mUserTracker.addCallback(mUserCallback, command -> {
            if (Looper.myLooper() == mMainHandler.getLooper()) {
                command.run();
            } else {
                mMainHandler.post(command);
            }
        });
        mSettings.observe(this::onSettingsChanged);
        onSettingsChanged();
    }

    private void onSettingsChanged() {
        mRingView.applySettings(mSettings);

        if (mMusicController != null) {
            mMusicController.applySettings(mSettings);
        }

        if (!mSettings.isTimerEnabled()) {
            clearTimerState();
        }

        if (mSettings.isEnabled()) {
            enableFeature();
        } else {
            disableFeature();
        }
    }

    private void enableFeature() {
        attachOverlay();

        final boolean wantDownloadTracking = mSettings.getDownloadPresentation()
                != CutoutProgressSettings.PRESENTATION_DISABLED;
        final boolean wantTimerTracking = mSettings.isTimerEnabled();
        final boolean wantNotificationAurora = mSettings.isAuroraEnabled()
                && mSettings.isAuroraNotificationsEnabled();

        final boolean startDownloadTracking =
                wantDownloadTracking && !mDownloadTrackingEnabled;
        final boolean stopDownloadTracking =
                !wantDownloadTracking && mDownloadTrackingEnabled;
        final boolean startTimerTracking =
                wantTimerTracking && !mTimerTrackingEnabled;
        final boolean stopTimerTracking =
                !wantTimerTracking && mTimerTrackingEnabled;

        mDownloadTrackingEnabled = wantDownloadTracking;
        mTimerTrackingEnabled = wantTimerTracking;
        mNotificationAuroraTrackingEnabled = wantNotificationAurora;

        if (wantDownloadTracking || wantTimerTracking || wantNotificationAurora) {
            registerPipelineListener();
        } else {
            unregisterPipelineListener();
        }

        if (stopDownloadTracking) {
            mTracker.reset();
        } else if (startDownloadTracking) {
            mTracker.reset();
            seedDownloadsFromPipeline();
        }

        if (stopTimerTracking) {
            clearTimerState();
        } else if (startTimerTracking) {
            clearTimerState();
            seedTimerFromPipeline();
        }

        if (mSettings.isChargingRingEnabled() || mSettings.isBatteryIndicatorEnabled()) {
            registerBatteryReceiver();
        } else {
            unregisterBatteryReceiver();
        }

        if (mMusicController != null) {
            boolean musicVisible = mSettings.isMusicRingEnabled()
                    && mSettings.getMusicPresentation()
                    != CutoutProgressSettings.PRESENTATION_DISABLED;
            boolean musicNeededForAurora = mSettings.isAuroraEnabled()
                    && mSettings.isAuroraMusicEnabled();
            if (musicVisible || musicNeededForAurora) {
                mMusicController.start();
            } else {
                mMusicController.stop();
            }
        }

        if (mSettings.isAuroraEnabled() && mSettings.isAuroraCallsEnabled()) {
            registerCallStateReceiver();
        } else {
            unregisterCallStateReceiver();
        }

        if (mSettings.isAuroraEnabled() && mSettings.isAuroraRecordingEnabled()) {
            registerRecordingCallback();
        } else {
            unregisterRecordingCallback();
        }
    }

    private void disableFeature() {
        unregisterPipelineListener();
        mDownloadTrackingEnabled = false;
        mTimerTrackingEnabled = false;
        mNotificationAuroraTrackingEnabled = false;
        if (mMusicController != null) {
            mMusicController.stop();
        }
        unregisterBatteryReceiver();
        unregisterCallStateReceiver();
        unregisterRecordingCallback();
        clearTimerState();
        if (mRingView != null) mRingView.clearTransientEffects();
        mTracker.reset();
        detachOverlay();
    }

    private void attachOverlay() {
        if (mOverlayAttached) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        // Match ScreenDecorations window semantics. In particular, do not use LAYOUT_NO_LIMITS:
        // it can make logical bounds/insets differ across OEM rotations and display modes.
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("CutoutProgressOverlay");

        WindowManager wm = mContext.getSystemService(WindowManager.class);
        if (wm != null) {
            wm.addView(mRingView, params);
            mOverlayAttached = true;
        }
    }

    private void detachOverlay() {
        if (!mOverlayAttached) return;
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        if (wm != null) {
            wm.removeView(mRingView);
            mOverlayAttached = false;
        }
    }

    private void registerPipelineListener() {
        if (mNotifListener != null) return;

        mNotifListener = new NotifCollectionListener() {
            @Override
            public void onEntryAdded(NotificationEntry entry) {
                if (!mSettings.isEnabled() || !isEntryForCurrentUser(entry)) return;
                if (mDownloadTrackingEnabled) {
                    mTracker.onNotificationChanged(entry);
                }
                if (mTimerTrackingEnabled) {
                    updateTimerFromNotification(entry);
                }
                if (mNotificationAuroraTrackingEnabled) {
                    triggerNotificationAurora(entry);
                }
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                if (!mSettings.isEnabled() || !isEntryForCurrentUser(entry)) return;
                if (mDownloadTrackingEnabled) {
                    mTracker.onNotificationChanged(entry);
                }
                if (mTimerTrackingEnabled) {
                    boolean wasCurrent = entry.getSbn().getKey().equals(mTimerKey);
                    long previousEnd = mTimerEndElapsedMs;
                    updateTimerFromNotification(entry);
                    if (wasCurrent && mTimerEndElapsedMs > previousEnd + 1000L) {
                        seedTimerFromPipeline();
                    }
                }
            }

            @Override
            public void onEntryRemoved(NotificationEntry entry, int reason) {
                if (!mSettings.isEnabled() || !isEntryForCurrentUser(entry)) return;
                if (mDownloadTrackingEnabled) {
                    mTracker.onNotificationRemoved(entry, reason);
                }
                if (mTimerTrackingEnabled && entry != null && entry.getSbn() != null
                        && entry.getSbn().getKey().equals(mTimerKey)) {
                    clearTimerState();
                    mMainHandler.post(CutoutProgressController.this::seedTimerFromPipeline);
                }
            }
        };
        mPipeline.addCollectionListener(mNotifListener);

    }

    private void unregisterPipelineListener() {
        if (mNotifListener == null) return;
        mPipeline.removeCollectionListener(mNotifListener);
        mNotifListener = null;
    }

    private void seedDownloadsFromPipeline() {
        if (!mSettings.isEnabled() || !mDownloadTrackingEnabled) return;
        for (NotificationEntry entry : mPipeline.getAllNotifs()) {
            if (isEntryForCurrentUser(entry)) {
                mTracker.onNotificationChanged(entry);
            }
        }
    }

    private void triggerNotificationAurora(NotificationEntry entry) {
        if (!mSettings.isAuroraEnabled() || !mSettings.isAuroraNotificationsEnabled()
                || entry == null || entry.getSbn() == null
                || entry.getSbn().getNotification() == null) {
            return;
        }
        Notification notification = entry.getSbn().getNotification();
        int color = notification.color;
        runOnMain(() -> mRingView.showNotificationAurora(
                color, mSettings.getAuroraNotificationDurationMs()));
    }

    private void updateTimerFromNotification(NotificationEntry entry) {
        if (!mSettings.isTimerEnabled() || entry == null || entry.getSbn() == null) return;
        Notification notification = entry.getSbn().getNotification();
        if (notification == null || !isLikelyClockNotification(entry, notification)) return;

        CountdownInfo countdown = extractCountdownInfo(notification);
        if (countdown == null) return;

        long nowElapsed = SystemClock.elapsedRealtime();
        long remaining = countdown.endElapsedMs - nowElapsed;
        if (remaining <= 0L) return;

        String key = entry.getSbn().getKey();

        // If several timers are active, visualize the one that expires first.
        if (mTimerKey != null && !mTimerKey.equals(key)
                && mTimerEndElapsedMs > nowElapsed && mTimerEndElapsedMs <= countdown.endElapsedMs) {
            return;
        }

        boolean newTimer = mTimerKey == null || !mTimerKey.equals(key);
        boolean wasRunning = mTimerRunning;
        long previousEndElapsedMs = mTimerEndElapsedMs;
        long endDelta = newTimer ? 0L : countdown.endElapsedMs - previousEndElapsedMs;

        mTimerKey = key;
        mTimerEndElapsedMs = countdown.endElapsedMs;
        mTimerRunning = countdown.running;

        if (newTimer || mTimerTotalMs <= 0L) {
            mTimerTotalMs = Math.max(1000L, remaining);
        } else if (wasRunning && countdown.running && Math.abs(endDelta) > 1000L) {
            // Preserve the user's progress across +time/-time edits. Pause/resume changes the
            // absolute end base too, but must not reset the ring back to 100%.
            mTimerTotalMs = Math.max(1000L, mTimerTotalMs + endDelta);
            mTimerTotalMs = Math.max(mTimerTotalMs, remaining);
        }

        removeTimerTick();
        updateTimerTick();
    }

    private boolean isLikelyClockNotification(NotificationEntry entry,
                                              Notification notification) {
        String pkg = entry.getSbn().getPackageName();
        String normalizedPkg = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        return Notification.CATEGORY_ALARM.equals(notification.category)
                || normalizedPkg.contains("clock")
                || normalizedPkg.contains("deskclock")
                || normalizedPkg.contains("timer");
    }

    private CountdownInfo extractCountdownInfo(Notification notification) {
        // Standard Notification.Builder countdown chronometer.
        if (notification.extras != null
                && notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                && notification.extras.getBoolean(
                        Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)) {
            long remainingWallMs = notification.when - System.currentTimeMillis();
            if (remainingWallMs > 0L) {
                return new CountdownInfo(
                        SystemClock.elapsedRealtime() + remainingWallMs, true);
            }
        }

        // DeskClock and several OEM clock apps use a custom RemoteViews Chronometer instead of
        // Notification.when. Inflate only clock/alarm notifications and read the actual countdown
        // base so the ring follows the same elapsed-realtime source as the Clock UI.
        CountdownInfo info = extractCountdownInfo(notification.contentView);
        if (info == null) info = extractCountdownInfo(notification.bigContentView);
        if (info == null) info = extractCountdownInfo(notification.headsUpContentView);
        return info;
    }

    private CountdownInfo extractCountdownInfo(RemoteViews remoteViews) {
        if (remoteViews == null) return null;
        try {
            FrameLayout parent = new FrameLayout(mContext);
            View root = remoteViews.apply(mContext, parent);
            return findCountdownChronometer(root);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CountdownInfo findCountdownChronometer(View view) {
        if (view instanceof Chronometer) {
            Chronometer chronometer = (Chronometer) view;
            if (chronometer.isCountDown()) {
                long base = chronometer.getBase();
                boolean started = readChronometerStarted(chronometer);
                chronometer.stop();
                if (base > SystemClock.elapsedRealtime()) {
                    return new CountdownInfo(base, started);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int child = 0; child < group.getChildCount(); child++) {
                CountdownInfo info = findCountdownChronometer(group.getChildAt(child));
                if (info != null) return info;
            }
        }
        return null;
    }

    private boolean readChronometerStarted(Chronometer chronometer) {
        try {
            Field field = Chronometer.class.getDeclaredField("mStarted");
            field.setAccessible(true);
            return field.getBoolean(chronometer);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // If an OEM changes Chronometer internals, prefer showing a live countdown over
            // silently dropping timer support. A subsequent notification update can correct it.
            return true;
        }
    }

    private void seedTimerFromPipeline() {
        if (!mSettings.isEnabled() || !mSettings.isTimerEnabled()) return;
        for (NotificationEntry entry : mPipeline.getAllNotifs()) {
            if (isEntryForCurrentUser(entry)) updateTimerFromNotification(entry);
        }
    }

    private void updateTimerTick() {
        mMainHandler.removeCallbacks(mTimerTick);
        if (!mSettings.isEnabled() || !mSettings.isTimerEnabled()
                || mTimerKey == null || mTimerTotalMs <= 0L) {
            clearTimerState();
            return;
        }

        long remaining = mTimerEndElapsedMs - SystemClock.elapsedRealtime();
        if (remaining <= 0L) {
            clearTimerState();
            return;
        }

        float fraction = Math.max(0f, Math.min(1f, remaining / (float) mTimerTotalMs));
        mRingView.setTimerState(true, fraction);
        if (mTimerRunning) {
            mMainHandler.postDelayed(mTimerTick, 250L);
        }
    }

    private void removeTimerTick() {
        mMainHandler.removeCallbacks(mTimerTick);
    }

    private void clearTimerState() {
        removeTimerTick();
        mTimerKey = null;
        mTimerEndElapsedMs = 0L;
        mTimerTotalMs = 0L;
        mTimerRunning = false;
        if (mRingView != null) mRingView.setTimerState(false, 0f);
    }

    private void registerCallStateReceiver() {
        if (mCallReceiverRegistered) {
            seedCallState();
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            mContext.registerReceiver(mCallStateReceiver, filter);
            mCallReceiverRegistered = true;
            seedCallState();
        } catch (RuntimeException ignored) {
            mCallReceiverRegistered = false;
        }
    }

    private void seedCallState() {
        boolean inCall = false;
        try {
            TelecomManager telecom = mContext.getSystemService(TelecomManager.class);
            inCall = telecom != null && telecom.isInCall();
        } catch (RuntimeException ignored) {
        }
        final boolean active = inCall;
        runOnMain(() -> mRingView.setAuroraCallActive(active));
    }

    private void unregisterCallStateReceiver() {
        if (mCallReceiverRegistered) {
            try {
                mContext.unregisterReceiver(mCallStateReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        mCallReceiverRegistered = false;
        if (mRingView != null) mRingView.setAuroraCallActive(false);
    }

    private void registerRecordingCallback() {
        if (mRecordingCallbackRegistered) {
            seedRecordingState();
            return;
        }
        mAudioManager = mContext.getSystemService(AudioManager.class);
        if (mAudioManager == null) return;
        try {
            mAudioManager.registerAudioRecordingCallback(mRecordingCallback, mMainHandler);
            mRecordingCallbackRegistered = true;
            seedRecordingState();
        } catch (RuntimeException ignored) {
            mRecordingCallbackRegistered = false;
        }
    }

    private void seedRecordingState() {
        boolean active = false;
        try {
            List<AudioRecordingConfiguration> configs =
                    mAudioManager != null ? mAudioManager.getActiveRecordingConfigurations() : null;
            active = hasUserVisibleRecording(configs);
        } catch (RuntimeException ignored) {
        }
        final boolean recording = active;
        runOnMain(() -> mRingView.setAuroraRecordingActive(recording));
    }

    private boolean hasUserVisibleRecording(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return false;
        for (AudioRecordingConfiguration config : configs) {
            if (config == null || config.isClientSilenced()) continue;
            if (config.getClientAudioSource() == MediaRecorder.AudioSource.HOTWORD) continue;
            return true;
        }
        return false;
    }

    private void unregisterRecordingCallback() {
        if (mRecordingCallbackRegistered && mAudioManager != null) {
            try {
                mAudioManager.unregisterAudioRecordingCallback(mRecordingCallback);
            } catch (RuntimeException ignored) {
            }
        }
        mRecordingCallbackRegistered = false;
        mAudioManager = null;
        if (mRingView != null) mRingView.setAuroraRecordingActive(false);
    }

    private boolean isEntryForCurrentUser(NotificationEntry entry) {
        if (entry == null || entry.getSbn() == null || entry.getSbn().getUser() == null) {
            return false;
        }
        int entryUser = entry.getSbn().getUser().getIdentifier();
        if (entryUser == UserHandle.USER_ALL || entryUser == mUserTracker.getUserId()) {
            return true;
        }
        for (UserInfo profile : mUserTracker.getUserProfiles()) {
            if (profile != null && profile.id == entryUser) return true;
        }
        return false;
    }

    private void registerBatteryReceiver() {
        if (mBatteryReceiverRegistered) {
            // Re-evaluate immediately when settings change; ACTION_BATTERY_CHANGED is sticky.
            Intent sticky = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) mBatteryReceiver.onReceive(mContext, sticky);
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = mContext.registerReceiver(mBatteryReceiver, filter);
        mBatteryReceiverRegistered = true;
        if (sticky != null) mBatteryReceiver.onReceive(mContext, sticky);
    }

    private void unregisterBatteryReceiver() {
        if (!mBatteryReceiverRegistered) return;
        mContext.unregisterReceiver(mBatteryReceiver);
        mBatteryReceiverRegistered = false;
        runOnMain(() -> {
            mRingView.setChargingState(false, 0);
            mRingView.setBatteryIndicatorState(false, 0);
        });
    }

    private void refreshThemeDependentColors() {
        runOnMain(() -> {
            if (mRingView != null) mRingView.invalidate();
            if (mMusicController != null) mMusicController.onThemeChanged();
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == mMainHandler.getLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }

    private void bindTrackerToView() {
        mTracker.setOnProgress(progress ->
                runOnMain(() -> mRingView.setProgress(progress)));

        mTracker.setOnComplete(() ->
                runOnMain(() -> mRingView.setProgress(100)));

        mTracker.setOnError(() ->
                runOnMain(() -> mRingView.showError()));

        mTracker.setOnCountChanged(count ->
                runOnMain(() -> mRingView.setDownloadCount(count)));

        mTracker.setOnLabelChanged(label ->
                runOnMain(() -> mRingView.setFilenameHint(label)));
    }
}

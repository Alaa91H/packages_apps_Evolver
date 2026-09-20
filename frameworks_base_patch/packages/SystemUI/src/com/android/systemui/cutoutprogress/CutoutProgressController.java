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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.cutoutprogress.ring.CutoutRingView;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;

import javax.inject.Inject;

@SysUISingleton
public class CutoutProgressController implements CoreStartable {

    private final Context mContext;
    private final NotifPipeline mPipeline;
    private final Handler mMainHandler;
    private final UserTracker mUserTracker;

    private final CutoutProgressSettings mSettings;
    private final DownloadStateTracker mTracker;
    private CutoutRingView mRingView;

    private MusicRingController mMusicController;

    private boolean mOverlayAttached = false;
    private boolean mBatteryReceiverRegistered = false;
    private NotifCollectionListener mNotifListener;

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            // Tear down state from the previous user before reading the new user's Secure settings.
            disableFeature();
            mSettings.setUserId(newUser);
            onSettingsChanged();
        }
    };

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
            UserTracker userTracker) {
        mContext = context;
        mPipeline = notifPipeline;
        mMainHandler = mainHandler;
        mUserTracker = userTracker;
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

        if (mSettings.isEnabled()) {
            enableFeature();
        } else {
            disableFeature();
        }
    }

    private void enableFeature() {
        attachOverlay();
        if (mSettings.getDownloadPresentation()
                != CutoutProgressSettings.PRESENTATION_DISABLED) {
            registerPipelineListener();
        } else {
            unregisterPipelineListener();
            mTracker.reset();
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
            if (musicVisible) {
                mMusicController.start();
            } else {
                mMusicController.stop();
            }
        }
    }

    private void disableFeature() {
        unregisterPipelineListener();
        if (mMusicController != null) {
            mMusicController.stop();
        }
        unregisterBatteryReceiver();
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
                if (mSettings.isEnabled()) mTracker.onNotificationChanged(entry);
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                if (mSettings.isEnabled()) mTracker.onNotificationChanged(entry);
            }

            @Override
            public void onEntryRemoved(NotificationEntry entry, int reason) {
                if (mSettings.isEnabled()) mTracker.onNotificationRemoved(entry, reason);
            }
        };
        mPipeline.addCollectionListener(mNotifListener);

        // Collection listeners do not replay already-present notifications. Seed the tracker so
        // enabling/re-enabling the feature during an active transfer works immediately.
        for (NotificationEntry entry : mPipeline.getAllNotifs()) {
            mTracker.onNotificationChanged(entry);
        }
    }

    private void unregisterPipelineListener() {
        if (mNotifListener == null) return;
        mPipeline.removeCollectionListener(mNotifListener);
        mNotifListener = null;
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

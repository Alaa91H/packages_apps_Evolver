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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;

import com.android.systemui.cutoutprogress.CutoutProgressSettings;

import java.util.Objects;

public final class CutoutRingView extends View {

    private static final long BURN_IN_HIDE_MS = 10_000L;

    private static final long CHARGING_PULSE_INTERVAL_MS = 1500L;
    private static final long CHARGING_PULSE_DURATION_MS = 900L;

    private static final int SOURCE_NONE = 0;
    private static final int SOURCE_DOWNLOAD = 1;
    private static final int SOURCE_MUSIC = 2;
    private static final int SOURCE_CHARGING = 3;
    private static final int SOURCE_BATTERY = 4;

    private static final int[] RAINBOW_COLORS = {
            0xFFFF0000,
            0xFFFF7F00,
            0xFFFFFF00,
            0xFF00FF00,
            0xFF00FFFF,
            0xFF0000FF,
            0xFF8B00FF,
            0xFFFF0000,
    };

    private float mDp;
    private final CameraCutoutGeometryResolver mGeometryResolver;

    private final Path mCutoutPath = new Path();
    private final Path mScaledPath = new Path();
    private final Matrix mScaleMatrix = new Matrix();
    private final RectF mPathBounds = new RectF();
    private final RectF mArcBounds = new RectF();
    private boolean mHasCutout = false;
    private boolean mAutoGeometryActive = false;
    private boolean mResolvedPillLike = false;
    private int mGeometryRotation = Surface.ROTATION_0;

    private final OverlayAnimationHelper mAnim;
    private RingViewRenderer mRenderer;
    private CountBadgePainter mBadge;

    private final Paint mRingPaint = makePaint();
    private final Paint mShinePaint = makePaint();
    private final Paint mErrorPaint = makePaint();
    private final Paint mAnimPaint = makePaint();
    private final Paint mBgPaint = makePaint();
    private final Paint mChargingPaint = makePaint();
    private final TextPaint mPercentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mFilenamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Paint mRainbowPaint = makePaint();
    private SweepGradient mRainbowShader = null;
    private float mRainbowCx = Float.NaN;
    private float mRainbowCy = Float.NaN;
    private final Paint mMusicPaint = makePaint();
    private final Paint mMusicWavePaint = makePaint();

    private int mProgress = 0;
    private int mDownloadCount = 0;
    private String mFilenameHint = null;

    private long mDownloadStartMs = 0L;
    private long mLastProgressMs = 0L;
    private Runnable mPendingFinish = null;

    private boolean mIsCharging = false;
    private int mBatteryPct = 0;
    private boolean mChargingPulseEnabled = true;
    private float mChargingPulsePhase = 0f;
    private ValueAnimator mChargingPulseAnim = null;
    private float mChargingDisplayPct = 0f;
    private ValueAnimator mChargingLevelAnim = null;

    private boolean mIsBatteryIndicatorActive = false;
    private int mBatteryIndicatorPct = 0;
    private float mBatteryDisplayPct = 0f;
    private ValueAnimator mBatteryLevelAnim = null;

    private boolean mMusicPlaying = false;
    private float mMusicFraction = 0f;
    private float mMusicWavePhase = 0f;
    private ValueAnimator mMusicWaveAnim = null;

    private int sCfgRingColorMode;
    private int sCfgRingColor;
    private int sCfgErrorColor;
    private int sCfgFlashColor;
    private float sCfgStrokeDp;
    private float sCfgRingGap;
    private int sCfgOpacity;
    private boolean sCfgClockwise;
    private String sCfgFinishStyle;
    private int sCfgFinishHoldMs;
    private int sCfgFinishExitMs;
    private boolean sCfgFinishFlash;
    private boolean sCfgPulse;
    private boolean sCfgAutoGeometry = true;
    private boolean sCfgPathMode;
    private float sCfgScaleX;
    private float sCfgScaleY;
    private float sCfgOffsetXDp;
    private float sCfgOffsetYDp;
    private boolean sCfgBgRing;
    private int sCfgBgColor;
    private int sCfgBgOpacity;
    private boolean sCfgMinVis;
    private int sCfgMinVisMs;
    private boolean sCfgShowBadge;
    private float sCfgBadgeOffXDp;
    private float sCfgBadgeOffYDp;
    private float sCfgBadgeSp;
    private boolean sCfgPct;
    private float sCfgPctSp;
    private boolean sCfgPctBold;
    private String sCfgPctPos;
    private float sCfgPctOffXDp;
    private float sCfgPctOffYDp;
    private boolean sCfgFname;
    private float sCfgFnameSp;
    private boolean sCfgFnameBold;
    private String sCfgFnamePos;
    private float sCfgFnameOffXDp;
    private float sCfgFnameOffYDp;
    private int sCfgFnameMaxChars;
    private String sCfgFnameTruncate;
    private String sCfgEasing;
    private boolean sCfgChargingRing;
    private boolean sCfgChargingPulse;
    private boolean sCfgGlowEnabled;
    private float sCfgGlowRadiusDp;

    private int sCfgMusicOpacity = 85;
    private float sCfgMusicStrokeDp = 2f;
    private boolean sCfgMusicClockwise = true;
    private boolean sCfgMusicShowOnAod = false;
    private int sCfgMusicColor = 0xFF9C27B0;
    private int sCfgDownloadPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
    private int sCfgMusicPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
    private int sCfgPrimaryPriority = CutoutProgressSettings.PRIMARY_PRIORITY_DOWNLOAD;
    private float sCfgMultiRingSpacingDp = 5f;
    private boolean sCfgMusicWaveEnabled = false;
    private float sCfgMusicWaveAmplitudeDp = 2.5f;
    private int sCfgMusicWaveDensity = 48;
    private int sCfgMusicWaveSpeed = 100;

    public CutoutRingView(Context ctx) {
        super(ctx);
        mDp = ctx.getResources().getDisplayMetrics().density;
        mGeometryResolver = new CameraCutoutGeometryResolver(ctx);
        mAnim = new OverlayAnimationHelper(this);
        mRenderer = new CircleRingRenderer();
        mBadge = new CountBadgePainter(mDp);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        initPaints();
    }

    public void applySettings(CutoutProgressSettings s) {
        sCfgRingColorMode = s.getRingColorMode();
        sCfgRingColor = s.getRingColor();
        sCfgErrorColor = s.getErrorColor();
        sCfgFlashColor = s.getFinishFlashColor();
        sCfgStrokeDp = s.getStrokeWidthDp();
        sCfgRingGap = s.getRingGap();
        sCfgOpacity = s.getOpacity();
        sCfgClockwise = s.isClockwise();
        sCfgFinishStyle  = s.getFinishStyle();
        sCfgFinishHoldMs = s.getFinishHoldMs();
        sCfgFinishExitMs = s.getFinishExitMs();
        sCfgFinishFlash = s.isFinishUseFlash();
        sCfgPulse = s.isCompletionPulse();
        sCfgAutoGeometry = s.isAutoGeometryEnabled();
        sCfgPathMode = s.isPathMode();
        sCfgScaleX = s.getRingScaleX();
        sCfgScaleY = s.getRingScaleY();
        sCfgOffsetXDp = s.getRingOffsetXDp();
        sCfgOffsetYDp = s.getRingOffsetYDp();
        sCfgBgRing = s.isBgRingEnabled();
        sCfgBgColor = s.getBgRingColor();
        sCfgBgOpacity = s.getBgRingOpacity();
        sCfgMinVis = s.isMinVisEnabled();
        sCfgMinVisMs = s.getMinVisMs();
        sCfgShowBadge = s.isShowCountBadge();
        sCfgBadgeOffXDp = s.getBadgeOffsetXDp();
        sCfgBadgeOffYDp = s.getBadgeOffsetYDp();
        sCfgBadgeSp = s.getBadgeTextSizeSp();
        sCfgPct = s.isPercentEnabled();
        sCfgPctSp = s.getPercentTextSizeSp();
        sCfgPctBold = s.isPercentBold();
        sCfgPctPos = s.getPercentPosition();
        sCfgPctOffXDp = s.getPercentOffsetXDp();
        sCfgPctOffYDp = s.getPercentOffsetYDp();
        sCfgFname = s.isFilenameEnabled();
        sCfgFnameSp = s.getFilenameTextSizeSp();
        sCfgFnameBold = s.isFilenameBold();
        sCfgFnamePos = s.getFilenamePosition();
        sCfgFnameOffXDp = s.getFilenameOffsetXDp();
        sCfgFnameOffYDp = s.getFilenameOffsetYDp();
        sCfgFnameMaxChars= s.getFilenameMaxChars();
        sCfgFnameTruncate= s.getFilenameTruncateMode();
        sCfgEasing = s.getProgressEasing();
        sCfgChargingRing = s.isChargingRingEnabled();
        sCfgChargingPulse = s.isChargingPulseEnabled();
        sCfgGlowEnabled = s.isGlowEnabled();
        sCfgGlowRadiusDp = s.getGlowRadiusDp();
        sCfgDownloadPresentation = s.getDownloadPresentation();
        sCfgMusicPresentation = s.getMusicPresentation();
        sCfgPrimaryPriority = s.getPrimaryPriority();
        sCfgMultiRingSpacingDp = s.getMultiRingSpacingDp();
        sCfgMusicWaveEnabled = s.isMusicWaveEnabled();
        sCfgMusicWaveAmplitudeDp = s.getMusicWaveAmplitudeDp();
        sCfgMusicWaveDensity = s.getMusicWaveDensity();
        sCfgMusicWaveSpeed = s.getMusicWaveSpeed();
        sCfgMusicShowOnAod = s.isMusicShowOnAod();

        updateRendererForGeometry();

        if (!sCfgChargingRing && mIsCharging) {
            stopChargingAnimations();
        }
        mChargingPulseEnabled = sCfgChargingPulse;

        if (sCfgRingColorMode != CutoutProgressSettings.RING_COLOR_MODE_RAINBOW) {
            mRainbowShader = null;
            mRainbowCx = Float.NaN;
        }

        refreshPaints();
        recalcScaledPath();
        applyMusicSettings(
                s.getMusicOpacity(),
                s.getMusicStrokeWidthDp(),
                s.isMusicClockwise(),
                sCfgMusicColor);
        restartMusicWaveAnimation();
        requestApplyInsets();
        invalidate();
    }

    public void applyMusicSettings(int opacityPct, float strokeDp,
                                   boolean clockwise, int color) {
        sCfgMusicOpacity = opacityPct;
        sCfgMusicStrokeDp = strokeDp;
        sCfgMusicClockwise = clockwise;
        sCfgMusicColor = color;
        refreshMusicPaint();
        invalidate();
    }

    public void setMusicRingColor(int argb) {
        if (sCfgMusicColor == argb) return;
        sCfgMusicColor = argb;
        refreshMusicPaint();
        if (mMusicPlaying) invalidate();
    }

    public void setMusicProgress(float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (mMusicFraction == fraction) return;
        mMusicFraction = fraction;
        if (mMusicPlaying) invalidate();
    }

    public void setMusicPlaying(boolean playing) {
        if (mMusicPlaying == playing) return;
        mMusicPlaying = playing;
        updateMusicWaveAnimation();
        invalidate();
    }

    private void restartMusicWaveAnimation() {
        stopMusicWaveAnimation();
        updateMusicWaveAnimation();
    }

    private void updateMusicWaveAnimation() {
        if (!mMusicPlaying || !sCfgMusicWaveEnabled) {
            stopMusicWaveAnimation();
            return;
        }
        if (mMusicWaveAnim != null) return;

        long duration = (long) (2600f * 100f / Math.max(25, sCfgMusicWaveSpeed));
        duration = Math.max(700L, Math.min(8000L, duration));
        mMusicWaveAnim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        mMusicWaveAnim.setDuration(duration);
        mMusicWaveAnim.setRepeatCount(ValueAnimator.INFINITE);
        mMusicWaveAnim.setRepeatMode(ValueAnimator.RESTART);
        mMusicWaveAnim.setInterpolator(new LinearInterpolator());
        mMusicWaveAnim.addUpdateListener(a -> {
            mMusicWavePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        mMusicWaveAnim.start();
    }

    private void stopMusicWaveAnimation() {
        if (mMusicWaveAnim != null) {
            mMusicWaveAnim.cancel();
            mMusicWaveAnim = null;
        }
        mMusicWavePhase = 0f;
    }

    private int resolveRingColor() {
        switch (sCfgRingColorMode) {
            case CutoutProgressSettings.RING_COLOR_MODE_ACCENT:
                return resolveAccentColor();
            case CutoutProgressSettings.RING_COLOR_MODE_RAINBOW:
                return RAINBOW_COLORS[0];
            default:
                return sCfgRingColor;
        }
    }

    private int resolveAccentColor() {
        TypedValue tv = new TypedValue();
        boolean resolved = getContext().getTheme()
                .resolveAttribute(android.R.attr.colorAccent, tv, true);
        int base = resolved ? tv.data : 0xFF2196F3;

        boolean isDark = (getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (isDark) {
            return lightenColor(base, 0.50f);
        } else {
            return base;
        }
    }

    private static int lightenColor(int color, float fraction) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = (int)(r + (255 - r) * fraction);
        g = (int)(g + (255 - g) * fraction);
        b = (int)(b + (255 - b) * fraction);
        return Color.argb(Color.alpha(color),
                Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private SweepGradient requireRainbowShader(float cx, float cy) {
        if (mRainbowShader == null
                || Math.abs(cx - mRainbowCx) > 0.5f
                || Math.abs(cy - mRainbowCy) > 0.5f) {
            mRainbowShader = new SweepGradient(cx, cy, RAINBOW_COLORS, null);
            mRainbowCx = cx;
            mRainbowCy = cy;
        }
        return mRainbowShader;
    }

    private void applyRainbowShader(Paint paint, float cx, float cy) {
        SweepGradient shader = requireRainbowShader(cx, cy);
        Matrix m = new Matrix();
        m.setRotate(-90f, cx, cy);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
    }

    private static void clearShader(Paint paint) {
        paint.setShader(null);
    }

    public void setChargingState(boolean charging, int batteryPct) {
        boolean wasCharging = mIsCharging;
        mIsCharging = charging;
        mBatteryPct = batteryPct;

        if (!charging) {
            stopChargingAnimations();
            invalidate();
            return;
        }

        if (!wasCharging) {
            mChargingDisplayPct = 0f;
        }

        animateChargingLevelTo(batteryPct);

        if (mChargingPulseEnabled && sCfgChargingPulse && mChargingPulseAnim == null) {
            startChargingPulse();
        }
        invalidate();
    }

    public void setBatteryIndicatorState(boolean active, int batteryPct) {
        boolean wasActive = mIsBatteryIndicatorActive;
        mIsBatteryIndicatorActive = active;
        mBatteryIndicatorPct = batteryPct;

        if (!active) {
            stopBatteryIndicatorAnim();
            invalidate();
            return;
        }

        if (!wasActive) {
            mBatteryDisplayPct = 0f;
        }

        animateBatteryLevelTo(batteryPct);
        invalidate();
    }

    public void setChargingPulseEnabled(boolean enabled) {
        mChargingPulseEnabled = enabled;
        sCfgChargingPulse = enabled;
        if (!enabled) {
            stopChargingPulse();
        } else if (mIsCharging && mChargingPulseAnim == null) {
            startChargingPulse();
        }
    }

    private void animateChargingLevelTo(int targetPct) {
        if (mChargingLevelAnim != null) mChargingLevelAnim.cancel();
        float start = mChargingDisplayPct;
        float end = Math.max(0f, Math.min(100f, targetPct));
        if (Math.abs(end - start) < 0.5f) {
            mChargingDisplayPct = end;
            invalidate();
            return;
        }
        long dur = (long)(Math.abs(end - start) * 12f);
        dur = Math.max(200L, Math.min(dur, 1200L));
        mChargingLevelAnim = ValueAnimator.ofFloat(start, end);
        mChargingLevelAnim.setDuration(dur);
        mChargingLevelAnim.setInterpolator(new LinearInterpolator());
        mChargingLevelAnim.addUpdateListener(a -> {
            mChargingDisplayPct = (float) a.getAnimatedValue();
            invalidate();
        });
        mChargingLevelAnim.start();
    }

    private void startChargingPulse() {
        stopChargingPulse();
        mChargingPulseAnim = ValueAnimator.ofFloat(0f, 1f);
        mChargingPulseAnim.setDuration(CHARGING_PULSE_DURATION_MS);
        mChargingPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        mChargingPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        mChargingPulseAnim.setInterpolator(new LinearInterpolator());
        mChargingPulseAnim.setStartDelay(0);
        mChargingPulseAnim.addUpdateListener(a -> {
            mChargingPulsePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        mChargingPulseAnim.start();
    }

    private void stopChargingPulse() {
        if (mChargingPulseAnim != null) {
            mChargingPulseAnim.cancel();
            mChargingPulseAnim = null;
        }
        mChargingPulsePhase = 0f;
    }

    private void stopChargingAnimations() {
        stopChargingPulse();
        if (mChargingLevelAnim != null) {
            mChargingLevelAnim.cancel();
            mChargingLevelAnim = null;
        }
        mChargingDisplayPct = 0f;
    }

    private void animateBatteryLevelTo(int targetPct) {
        if (mBatteryLevelAnim != null) mBatteryLevelAnim.cancel();
        float start = mBatteryDisplayPct;
        float end   = Math.max(0f, Math.min(100f, targetPct));
        if (Math.abs(end - start) < 0.5f) {
            mBatteryDisplayPct = end;
            invalidate();
            return;
        }
        long dur = (long)(Math.abs(end - start) * 12f);
        dur = Math.max(200L, Math.min(dur, 1200L));
        mBatteryLevelAnim = ValueAnimator.ofFloat(start, end);
        mBatteryLevelAnim.setDuration(dur);
        mBatteryLevelAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mBatteryLevelAnim.addUpdateListener(a -> {
            mBatteryDisplayPct = (float) a.getAnimatedValue();
            invalidate();
        });
        mBatteryLevelAnim.start();
    }

    private void stopBatteryIndicatorAnim() {
        if (mBatteryLevelAnim != null) {
            mBatteryLevelAnim.cancel();
            mBatteryLevelAnim = null;
        }
        mBatteryDisplayPct = 0f;
    }

    public void setProgress(int value) {
        int pct = Math.max(0, Math.min(100, value));
        if (mProgress == pct) return;

        int prev = mProgress;
        mProgress = pct;
        mLastProgressMs = System.currentTimeMillis();

        removeCallbacks(mBurnInHide);
        if (pct > 0 && pct < 100) {
            postDelayed(mBurnInHide, BURN_IN_HIDE_MS);
        }

        if (prev == 0 && pct > 0) {
            mDownloadStartMs = System.currentTimeMillis();
            cancelPendingFinish();
        }

        if (pct == 100 && !mAnim.isFinishAnimating) {
            long elapsed = System.currentTimeMillis() - mDownloadStartMs;
            long remaining = (sCfgMinVis ? sCfgMinVisMs : 0) - elapsed;
            if (remaining > 0 && mDownloadStartMs > 0) {
                mPendingFinish = () -> { mPendingFinish = null; beginFinishAnim(); };
                postDelayed(mPendingFinish, remaining);
            } else {
                beginFinishAnim();
            }
        } else if (pct > 0 && pct < 100 && mAnim.isFinishAnimating) {
            mAnim.cancelFinish();
        } else if (pct == 0) {
            mDownloadStartMs = 0L;
            cancelPendingFinish();
        }

        invalidate();
    }

    public void setDownloadCount(int count) {
        if (mDownloadCount != count) { mDownloadCount = count; invalidate(); }
    }

    public void setFilenameHint(String hint) {
        if (!Objects.equals(mFilenameHint, hint)) { mFilenameHint = hint; invalidate(); }
    }

    public void showError() {
        mAnim.startError(() -> setProgress(0));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        refreshDensityIfNeeded();
        mCutoutPath.reset();
        mScaledPath.reset();
        mHasCutout = false;
        mAutoGeometryActive = false;
        mResolvedPillLike = false;

        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            mGeometryRotation = resolveCutoutRotation(cutout);

            if (sCfgAutoGeometry) {
                CameraCutoutGeometryResolver.ResolvedGeometry geometry =
                        mGeometryResolver.resolve(cutout);
                if (geometry != null) {
                    mCutoutPath.set(geometry.path);
                    mHasCutout = true;
                    mAutoGeometryActive = true;
                    mResolvedPillLike = geometry.pillLike;
                    mGeometryRotation = geometry.rotation;
                }
            }

            if (!mHasCutout) {
                mHasCutout = extractPreferredCutout(cutout);
                mResolvedPillLike = sCfgPathMode;
            }
        }

        updateRendererForGeometry();
        mRainbowShader = null;
        mRainbowCx = Float.NaN;
        mRainbowCy = Float.NaN;

        if (mHasCutout) {
            recalcScaledPath();
        }
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    private boolean extractPreferredCutout(DisplayCutout cutout) {
        Rect target = cutout.getBoundingRectTop();
        if (target == null || target.isEmpty()) {
            target = chooseSmallestCutout(cutout);
        }

        Path nativePath = null;
        try {
            nativePath = cutout.getCutoutPath();
        } catch (NoSuchMethodError ignored) {
        }

        if (nativePath != null && !nativePath.isEmpty()) {
            if (target != null && !target.isEmpty()) {
                Path selected = new Path(nativePath);
                Path clip = new Path();
                clip.addRect(new RectF(target), Path.Direction.CW);
                if (selected.op(clip, Path.Op.INTERSECT) && !selected.isEmpty()) {
                    mCutoutPath.set(selected);
                    return true;
                }
            } else {
                mCutoutPath.set(nativePath);
                return true;
            }
        }

        if (target != null && !target.isEmpty()) {
            RectF rect = new RectF(target);
            float radius = Math.min(rect.width(), rect.height()) / 2f;
            mCutoutPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            return true;
        }

        return false;
    }

    private Rect chooseSmallestCutout(DisplayCutout cutout) {
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect == null || rect.isEmpty()) continue;
            long area = (long) rect.width() * rect.height();
            if (area > 0 && area < bestArea) {
                best = rect;
                bestArea = area;
            }
        }
        return best;
    }

    private int resolveCutoutRotation(DisplayCutout cutout) {
        try {
            return cutout.getCutoutPathParserInfo().getRotation();
        } catch (Throwable ignored) {
            Display display = getDisplay();
            return display != null ? display.getRotation() : Surface.ROTATION_0;
        }
    }

    private void updateRendererForGeometry() {
        boolean needPath = mAutoGeometryActive ? mResolvedPillLike : sCfgPathMode;
        if (needPath && !(mRenderer instanceof CapsuleRingRenderer)) {
            mRenderer = new CapsuleRingRenderer();
        } else if (!needPath && !(mRenderer instanceof CircleRingRenderer)) {
            mRenderer = new CircleRingRenderer();
        }
    }

    private void refreshDensityIfNeeded() {
        float density = getResources().getDisplayMetrics().density;
        if (!Float.isFinite(density) || density <= 0f) density = 1f;
        if (Math.abs(density - mDp) < 0.001f) return;

        mDp = density;
        mBadge = new CountBadgePainter(mDp);
        refreshPaints();
        refreshMusicPaint();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshDensityIfNeeded();
        requestApplyInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            requestApplyInsets();
        }
    }

    private void recalcScaledPath() {
        mCutoutPath.computeBounds(mPathBounds, true);
        mScaleMatrix.setScale(sCfgRingGap, sCfgRingGap,
                mPathBounds.centerX(), mPathBounds.centerY());
        mScaledPath.reset();
        mCutoutPath.transform(mScaleMatrix, mScaledPath);
    }

    private final Runnable mBurnInHide = this::invalidate;

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mHasCutout) return;

        int effectivePct = mAnim.isGeometryPreviewActive() ? 100
                : mAnim.isDynamicPreviewActive() ? mAnim.previewProgress
                : mProgress;

        boolean preview = mAnim.isGeometryPreviewActive() || mAnim.isDynamicPreviewActive();
        boolean burnedOut = !preview
                && mDownloadCount == 0
                && effectivePct > 0 && effectivePct < 100
                && mLastProgressMs > 0
                && System.currentTimeMillis() - mLastProgressMs >= BURN_IN_HIDE_MS;

        boolean downloadActive = preview
                || mAnim.isErrorAnimating
                || mAnim.isFinishAnimating
                || (effectivePct > 0 && effectivePct < 100 && !burnedOut)
                || mPendingFinish != null;
        if (!preview && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_DISABLED) {
            downloadActive = false;
        }

        boolean musicActive = mMusicPlaying
                && (sCfgMusicShowOnAod || !isDisplayDozing())
                && sCfgMusicPresentation != CutoutProgressSettings.PRESENTATION_DISABLED;

        boolean downloadPrimary = preview || (downloadActive
                && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY);
        boolean musicPrimary = musicActive
                && sCfgMusicPresentation == CutoutProgressSettings.PRESENTATION_PRIMARY;

        int primarySource = SOURCE_NONE;
        if (downloadPrimary && musicPrimary) {
            boolean forceDownload = mAnim.isErrorAnimating
                    || mAnim.isFinishAnimating || mPendingFinish != null;
            primarySource = forceDownload
                    || sCfgPrimaryPriority == CutoutProgressSettings.PRIMARY_PRIORITY_DOWNLOAD
                    ? SOURCE_DOWNLOAD : SOURCE_MUSIC;
        } else if (downloadPrimary) {
            primarySource = SOURCE_DOWNLOAD;
        } else if (musicPrimary) {
            primarySource = SOURCE_MUSIC;
        }

        if (primarySource == SOURCE_NONE && mIsCharging && sCfgChargingRing) {
            primarySource = SOURCE_CHARGING;
        } else if (primarySource == SOURCE_NONE
                && mIsBatteryIndicatorActive && !mIsCharging) {
            primarySource = SOURCE_BATTERY;
        }

        boolean downloadIndependent = !preview && downloadActive
                && sCfgDownloadPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        boolean musicIndependent = musicActive
                && sCfgMusicPresentation == CutoutProgressSettings.PRESENTATION_INDEPENDENT;

        if (primarySource == SOURCE_NONE && !downloadIndependent && !musicIndependent) return;

        drawSource(canvas, primarySource, effectivePct, 0f);

        boolean downloadConfiguredIndependent = sCfgDownloadPresentation
                == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        boolean musicConfiguredIndependent = sCfgMusicPresentation
                == CutoutProgressSettings.PRESENTATION_INDEPENDENT;
        int downloadLane = 1;
        int musicLane = 1;
        if (downloadConfiguredIndependent && musicConfiguredIndependent) {
            if (sCfgPrimaryPriority == CutoutProgressSettings.PRIMARY_PRIORITY_MUSIC) {
                musicLane = 1;
                downloadLane = 2;
            } else {
                downloadLane = 1;
                musicLane = 2;
            }
        }

        if (downloadIndependent) {
            drawSource(canvas, SOURCE_DOWNLOAD, effectivePct,
                    downloadLane * sCfgMultiRingSpacingDp);
        }
        if (musicIndependent) {
            drawSource(canvas, SOURCE_MUSIC, effectivePct,
                    musicLane * sCfgMultiRingSpacingDp);
        }
    }

    private boolean isDisplayDozing() {
        Display display = getDisplay();
        if (display == null) return false;
        int state = display.getState();
        return state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
    }

    private void drawSource(Canvas canvas, int source, int effectivePct, float laneOffsetDp) {
        switch (source) {
            case SOURCE_DOWNLOAD:
                drawDownloadRing(canvas, effectivePct, laneOffsetDp);
                break;
            case SOURCE_MUSIC:
                drawMusicRing(canvas, laneOffsetDp);
                break;
            case SOURCE_CHARGING:
                drawChargingRing(canvas, laneOffsetDp);
                break;
            case SOURCE_BATTERY:
                drawBatteryIndicatorRing(canvas, laneOffsetDp);
                break;
            default:
                break;
        }
    }

    private void drawDownloadRing(Canvas canvas, int effectivePct, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        if (mAnim.isErrorAnimating) {
            mErrorPaint.setAlpha((int) (mAnim.errorAlpha * 255));
            mRenderer.drawFullRing(canvas, mErrorPaint);
            return;
        }

        boolean scaled = mAnim.displayScale != 1f;
        if (scaled) {
            canvas.save();
            canvas.scale(mAnim.displayScale, mAnim.displayScale,
                    mArcBounds.centerX(), mArcBounds.centerY());
        }

        int activeRingColor = resolveRingColor();
        mAnimPaint.set(mRingPaint);
        mAnimPaint.setColor(activeRingColor);
        mAnimPaint.setStrokeWidth(sCfgStrokeDp * mDp);
        int alpha = (int) (sCfgOpacity * 255f / 100f
                * mAnim.displayAlpha * mAnim.completionPulseAlpha);
        mAnimPaint.setAlpha(alpha);
        mAnimPaint.setShadowLayer(
                sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                0f, 0f, activeRingColor);

        if (sCfgRingColorMode == CutoutProgressSettings.RING_COLOR_MODE_RAINBOW) {
            applyRainbowShader(mAnimPaint, mArcBounds.centerX(), mArcBounds.centerY());
        } else {
            clearShader(mAnimPaint);
        }

        if (mAnim.successColorBlend > 0f) {
            int flashColor = sCfgFinishFlash ? sCfgFlashColor
                    : brighten(activeRingColor, mAnim.successColorBlend);
            mAnimPaint.setColor(blendColors(activeRingColor, flashColor,
                    mAnim.successColorBlend));
            mAnimPaint.setShadowLayer(
                    sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                    0f, 0f, mAnimPaint.getColor());
            clearShader(mAnimPaint);
        }

        boolean isActive = effectivePct > 0 && effectivePct < 100
                || mAnim.isGeometryPreviewActive()
                || mAnim.isDynamicPreviewActive();

        if (sCfgBgRing && !mAnim.isFinishAnimating && isActive) {
            mBgPaint.setAlpha((int) (sCfgBgOpacity * 255 / 100 * mAnim.displayAlpha));
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        if (mAnim.isFinishAnimating) {
            drawFinish(canvas, mAnimPaint);
        } else {
            float sweep = eased(effectivePct, sCfgEasing);
            mRenderer.drawProgress(canvas, sweep, sCfgClockwise, mAnimPaint);
            if (isActive) drawLabels(canvas, effectivePct, activeRingColor);
        }

        boolean showBadge = !mAnim.isDynamicPreviewActive() && sCfgShowBadge
                && (mDownloadCount > 1 || mAnim.isGeometryPreviewActive());
        if (showBadge) {
            float badgeCx = mArcBounds.centerX() + sCfgBadgeOffXDp * mDp;
            float badgeTop = mArcBounds.bottom + 4f * mDp + sCfgBadgeOffYDp * mDp;
            int badgeN = mAnim.isGeometryPreviewActive() ? 3 : mDownloadCount;
            mBadge.draw(canvas, badgeCx, badgeTop, badgeN, sCfgOpacity);
        }

        if (scaled) canvas.restore();
    }

    private void drawMusicRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        mMusicPaint.setAlpha(sCfgMusicOpacity * 255 / 100);
        mRenderer.drawProgress(canvas, mMusicFraction, sCfgMusicClockwise, mMusicPaint);
        if (sCfgMusicWaveEnabled) {
            drawMusicWave(canvas);
        }
    }

    private void drawMusicWave(Canvas canvas) {
        int density = Math.max(16, Math.min(96, sCfgMusicWaveDensity));
        float a = mArcBounds.width() / 2f;
        float b = mArcBounds.height() / 2f;
        if (a <= 0f || b <= 0f) return;

        float cx = mArcBounds.centerX();
        float cy = mArcBounds.centerY();
        float amplitudeBase = Math.max(0.5f, sCfgMusicWaveAmplitudeDp) * mDp;
        float pad = (sCfgMusicStrokeDp * 0.65f + 0.8f) * mDp;
        mMusicWavePaint.setColor(sCfgMusicColor);
        mMusicWavePaint.setAlpha(sCfgMusicOpacity * 255 / 100);

        for (int i = 0; i < density; i++) {
            float t = (float) (Math.PI * 2.0 * i / density);
            float cos = (float) Math.cos(t);
            float sin = (float) Math.sin(t);
            float x = cx + a * cos;
            float y = cy + b * sin;

            float nx = cos / a;
            float ny = sin / b;
            float norm = (float) Math.sqrt(nx * nx + ny * ny);
            if (norm <= 0f) continue;
            nx /= norm;
            ny /= norm;

            float wave = 0.55f
                    + 0.25f * (float) Math.sin(t * 3f + mMusicWavePhase)
                    + 0.20f * (float) Math.sin(t * 7f - mMusicWavePhase * 1.7f);
            wave = Math.max(0.12f, Math.min(1f, wave));
            float amplitude = amplitudeBase * wave;
            canvas.drawLine(
                    x + nx * pad, y + ny * pad,
                    x + nx * (pad + amplitude), y + ny * (pad + amplitude),
                    mMusicWavePaint);
        }
    }

    private void drawChargingRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        int baseAlpha = sCfgOpacity * 255 / 100;
        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int levelColor = chargingColor(mChargingDisplayPct);
        applyStroke(mChargingPaint, levelColor, sCfgStrokeDp * mDp, baseAlpha);
        if (mChargingPulseEnabled && sCfgChargingPulse && mChargingPulseAnim != null) {
            float drawFraction = mChargingPulsePhase * (mChargingDisplayPct / 100f);
            drawSymmetricArc(canvas, drawFraction, mChargingPaint);
        } else {
            drawSymmetricArc(canvas, mChargingDisplayPct / 100f, mChargingPaint);
        }
    }

    private void drawBatteryIndicatorRing(Canvas canvas, float laneOffsetDp) {
        computeArcBounds(laneOffsetDp);
        mRenderer.updateBounds(mArcBounds);

        int baseAlpha = sCfgOpacity * 255 / 100;
        if (sCfgBgRing) {
            mBgPaint.setAlpha(sCfgBgOpacity * 255 / 100);
            mRenderer.drawFullRing(canvas, mBgPaint);
        }

        int levelColor = chargingColor(mBatteryDisplayPct);
        applyStroke(mChargingPaint, levelColor, sCfgStrokeDp * mDp, baseAlpha);
        mRenderer.drawProgress(canvas, mBatteryDisplayPct / 100f, true, mChargingPaint);
    }

    private void drawSymmetricArc(Canvas canvas, float fraction, Paint paint) {
        if (fraction <= 0f) return;
        fraction = Math.min(fraction, 1f);
        float sweep = fraction * 180f;

        canvas.drawArc(mArcBounds, 90f - sweep, sweep, false, paint);
        canvas.drawArc(mArcBounds, 90f, sweep, false, paint);
    }

    private static int chargingColor(float pct) {
        if (pct < 30f) return 0xFFF44336;
        if (pct < 60f) return 0xFFFF9800;
        return 0xFF4CAF50;
    }

    private void drawFinish(Canvas canvas, Paint paint) {
        if ("segmented".equals(sCfgFinishStyle)) {
            mRenderer.drawSegmented(canvas,
                    OverlayAnimationHelper.SEGMENT_COUNT,
                    OverlayAnimationHelper.SEGMENT_GAP_DEG,
                    OverlayAnimationHelper.SEGMENT_ARC_DEG,
                    mAnim.segmentHighlight,
                    paint, mShinePaint, mAnim.displayAlpha);
        } else {
            mRenderer.drawFullRing(canvas, paint);
        }
    }

    private void drawLabels(Canvas canvas, int pct, int ringColor) {
        float pad = 4f * mDp;
        int alpha = sCfgOpacity * 255 / 100;

        if (sCfgPct) {
            String text = pct + "%";
            float tw = mPercentPaint.measureText(text);
            float[] pos = labelXY(sCfgPctPos, pad, mPercentPaint.getTextSize(), tw);
            mPercentPaint.setColor(ringColor);
            mPercentPaint.setAlpha(alpha);
            canvas.drawText(text, pos[0] + sCfgPctOffXDp * mDp,
                    pos[1] + sCfgPctOffYDp * mDp, mPercentPaint);
        }

        boolean geoPreview = mAnim.isGeometryPreviewActive();
        String fname = mFilenameHint != null ? mFilenameHint
                : geoPreview ? "EvolutionX-16.0-arm64.zip" : null;

        if (sCfgFname && fname != null && (mDownloadCount <= 1 || geoPreview)) {
            String display = truncate(fname, sCfgFnameMaxChars, sCfgFnameTruncate);
            float[] pos = labelXY(sCfgFnamePos, pad, mFilenamePaint.getTextSize(), null);
            mFilenamePaint.setColor(ringColor);
            mFilenamePaint.setAlpha(alpha);
            canvas.drawText(display, pos[0] + sCfgFnameOffXDp * mDp,
                    pos[1] + sCfgFnameOffYDp * mDp, mFilenamePaint);
        }
    }

    private float[] labelXY(String position, float pad, float textHeight, Float textW) {
        switch (position) {
            case "left":
                return new float[]{
                        textW != null ? mArcBounds.left - textW / 2f - pad
                                      : mArcBounds.left - pad,
                        mArcBounds.centerY() + textHeight / 3f};
            case "top":
                return new float[]{mArcBounds.centerX(), mArcBounds.top - pad};
            case "bottom":
                return new float[]{mArcBounds.centerX(),
                        mArcBounds.bottom + textHeight + pad};
            case "top_left":
                return new float[]{mArcBounds.left - pad, mArcBounds.top - pad};
            case "top_right":
                return new float[]{mArcBounds.right + pad, mArcBounds.top - pad};
            case "bottom_left":
                return new float[]{mArcBounds.left - pad,
                        mArcBounds.bottom + textHeight + pad};
            case "bottom_right":
                return new float[]{mArcBounds.right + pad,
                        mArcBounds.bottom + textHeight + pad};
            default:
                return new float[]{
                        textW != null ? mArcBounds.right + textW / 2f + pad
                                      : mArcBounds.right + pad,
                        mArcBounds.centerY() + textHeight / 3f};
        }
    }

    private void computeArcBounds(float laneOffsetDp) {
        mScaledPath.computeBounds(mArcBounds, true);
        float cx = mArcBounds.centerX();
        float cy = mArcBounds.centerY();
        float halfW;
        float halfH;

        if (mAutoGeometryActive) {
            // Automatic geometry is already expressed in the current logical display coordinate
            // space. Do not rotate, stretch or offset it again.
            halfW = mArcBounds.width() / 2f;
            halfH = mArcBounds.height() / 2f;
        } else {
            float[] offRotated = rotateOffset(sCfgOffsetXDp, sCfgOffsetYDp);
            cx += offRotated[0];
            cy += offRotated[1];

            float scaleX = sCfgScaleX;
            float scaleY = sCfgScaleY;
            if (mGeometryRotation == Surface.ROTATION_90
                    || mGeometryRotation == Surface.ROTATION_270) {
                // User calibration is defined in natural display axes. Rotate the calibration
                // with the hardware instead of re-applying portrait X/Y to landscape axes.
                float tmp = scaleX;
                scaleX = scaleY;
                scaleY = tmp;
            }

            if (sCfgPathMode) {
                halfW = mArcBounds.width() / 2f * scaleX;
                halfH = mArcBounds.height() / 2f * scaleY;
            } else {
                float halfBase = Math.min(mArcBounds.width(), mArcBounds.height()) / 2f;
                halfW = halfBase * scaleX;
                halfH = halfBase * scaleY;
            }
        }

        mArcBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        if (laneOffsetDp > 0f) {
            float px = laneOffsetDp * mDp;
            mArcBounds.inset(-px, -px);
        }
    }

    private float[] rotateOffset(float dx, float dy) {
        switch (mGeometryRotation) {
            case Surface.ROTATION_90:
                return new float[]{dy * mDp, -dx * mDp};
            case Surface.ROTATION_180:
                return new float[]{-dx * mDp, -dy * mDp};
            case Surface.ROTATION_270:
                return new float[]{-dy * mDp, dx * mDp};
            default:
                return new float[]{dx * mDp, dy * mDp};
        }
    }

    private void initPaints() {
        sCfgRingColorMode = CutoutProgressSettings.RING_COLOR_MODE_ACCENT;
        sCfgRingColor = 0xFF2196F3;
        sCfgErrorColor = 0xFFF44336;
        sCfgFlashColor = Color.WHITE;
        sCfgStrokeDp = 2f;
        sCfgRingGap = 1.155f;
        sCfgOpacity = 90;
        sCfgBgColor = 0xFF808080;
        sCfgBgOpacity = 30;
        sCfgPctSp = 8f;
        sCfgPctBold = true;
        sCfgFnameSp = 7f;
        sCfgBadgeSp = 10f;
        sCfgPctPos = "right";
        sCfgFnamePos = "top_right";
        sCfgFnameTruncate = "middle";
        sCfgFnameMaxChars = 20;
        sCfgEasing = "linear";
        sCfgClockwise = true;
        sCfgFinishStyle= "pop";
        sCfgAutoGeometry = true;
        sCfgScaleX = 1.05f;
        sCfgScaleY = 0.60f;
        sCfgOffsetXDp = 0f;
        sCfgOffsetYDp = 1.5f;
        sCfgDownloadPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
        sCfgMusicPresentation = CutoutProgressSettings.PRESENTATION_PRIMARY;
        sCfgPrimaryPriority = CutoutProgressSettings.PRIMARY_PRIORITY_DOWNLOAD;
        sCfgMultiRingSpacingDp = 3f;
        sCfgMusicWaveEnabled = false;
        sCfgMusicWaveAmplitudeDp = 2.5f;
        sCfgMusicWaveDensity = 48;
        sCfgMusicWaveSpeed = 100;
        sCfgBgRing = sCfgMinVis = true;
        sCfgMinVisMs = 500;
        sCfgChargingRing = true;
        sCfgChargingPulse = true;
        sCfgGlowEnabled = false;
        sCfgGlowRadiusDp = 4f;
        refreshPaints();
        refreshMusicPaint();
    }

    private void refreshPaints() {
        float stroke = sCfgStrokeDp * mDp;
        int baseColor = (sCfgRingColorMode == CutoutProgressSettings.RING_COLOR_MODE_CUSTOM)
                ? sCfgRingColor : resolveRingColor();
        applyStroke(mRingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);
        mRingPaint.setShadowLayer(
                sCfgGlowEnabled ? sCfgGlowRadiusDp * mDp : 0f,
                0f, 0f, baseColor);
        applyStroke(mShinePaint, sCfgFlashColor, stroke * 1.2f, 255);
        applyStroke(mErrorPaint, sCfgErrorColor, stroke * 1.5f, 255);
        applyStroke(mBgPaint, sCfgBgColor, stroke, sCfgBgOpacity * 255 / 100);
        applyStroke(mChargingPaint, baseColor, stroke, sCfgOpacity * 255 / 100);

        mRainbowPaint.setStyle(Paint.Style.STROKE);
        mRainbowPaint.setAntiAlias(true);
        mRainbowPaint.setStrokeWidth(stroke);
        mRainbowPaint.setStrokeCap(Paint.Cap.BUTT);
        mRainbowPaint.setAlpha(sCfgOpacity * 255 / 100);

        mPercentPaint.setTypeface(sCfgPctBold
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mPercentPaint.setTextSize(spToPx(sCfgPctSp));
        mPercentPaint.setTextAlign(Paint.Align.CENTER);

        mFilenamePaint.setTypeface(sCfgFnameBold
                ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mFilenamePaint.setTextSize(spToPx(sCfgFnameSp));
        mFilenamePaint.setTextAlign(Paint.Align.LEFT);

        mBadge.applyConfig(baseColor, sCfgBadgeSp, mDp);
    }

    private void refreshMusicPaint() {
        applyStroke(mMusicPaint,
                sCfgMusicColor,
                sCfgMusicStrokeDp * mDp,
                sCfgMusicOpacity * 255 / 100);
        applyStroke(mMusicWavePaint,
                sCfgMusicColor,
                Math.max(1f, sCfgMusicStrokeDp * 0.45f * mDp),
                sCfgMusicOpacity * 255 / 100);
        mMusicWavePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private static void applyStroke(Paint p, int color, float width, int alpha) {
        p.setStyle(Paint.Style.STROKE);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void beginFinishAnim() {
        mAnim.startFinish(sCfgFinishStyle, sCfgFinishHoldMs, sCfgFinishExitMs,
                sCfgPulse, () -> setProgress(0));
    }

    private void cancelPendingFinish() {
        if (mPendingFinish != null) {
            removeCallbacks(mPendingFinish);
            mPendingFinish = null;
        }
    }

    private static float eased(int pct, String mode) {
        float v = pct / 100f;
        switch (mode) {
            case "accelerate": return v * v;
            case "decelerate": return 1f - (1f - v) * (1f - v);
            case "ease_in_out": return v < .5f ? 2*v*v : 1f - (float)Math.pow(-2*v+2,2)/2f;
            default: return v;
        }
    }

    private static int brighten(int c, float f) {
        return Color.argb(Color.alpha(c),
                Math.min(255, (int)(Color.red(c) + (255 - Color.red(c)) * f)),
                Math.min(255, (int)(Color.green(c) + (255 - Color.green(c)) * f)),
                Math.min(255, (int)(Color.blue(c) + (255 - Color.blue(c)) * f)));
    }

    private static int blendColors(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        return Color.argb(Color.alpha(c1),
                (int)(Color.red(c1)*inv + Color.red(c2)*ratio),
                (int)(Color.green(c1)*inv + Color.green(c2)*ratio),
                (int)(Color.blue(c1)*inv + Color.blue(c2)*ratio));
    }

    private static String truncate(String s, int max, String mode) {
        if (s.length() <= max) return s;
        String e = "\u2026";
        int avail = max - 1;
        if (avail <= 0) return e;
        switch (mode) {
            case "start": return e + s.substring(s.length() - avail);
            case "end": return s.substring(0, avail) + e;
            default: {
                int head = (avail + 1) / 2;
                int tail = avail - head;
                return s.substring(0, head) + e + s.substring(s.length() - tail);
            }
        }
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }

    private static Paint makePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }
}

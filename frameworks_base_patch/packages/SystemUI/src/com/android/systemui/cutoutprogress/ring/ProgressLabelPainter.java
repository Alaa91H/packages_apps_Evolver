/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.cutoutprogress.ring;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Renders percentage and filename labels without allocating position arrays during frames.
 *
 * <p>The filename visibility gate is intentionally supplied by the caller so lockscreen/AOD
 * privacy policy can be applied in one place without coupling this painter to SystemUI state.</p>
 */
final class ProgressLabelPainter {

    private final TextPaint mPercentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mFilenamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mPosition = new float[2];

    private float mDp;
    private float mScaledDensity;

    private boolean mPercentEnabled = false;
    private float mPercentSizeSp = 8f;
    private boolean mPercentBold = true;
    private String mPercentPosition = "right";
    private float mPercentOffsetXDp = 0f;
    private float mPercentOffsetYDp = 0f;

    private boolean mFilenameEnabled = false;
    private float mFilenameSizeSp = 7f;
    private boolean mFilenameBold = false;
    private String mFilenamePosition = "top_right";
    private float mFilenameOffsetXDp = 0f;
    private float mFilenameOffsetYDp = 0f;
    private int mFilenameMaxChars = 20;
    private String mFilenameTruncate = "middle";

    ProgressLabelPainter(float density, float scaledDensity) {
        setDensity(density, scaledDensity);
        refreshTextPaints();
    }

    void setDensity(float density, float scaledDensity) {
        mDp = Float.isFinite(density) && density > 0f ? density : 1f;
        mScaledDensity = Float.isFinite(scaledDensity) && scaledDensity > 0f
                ? scaledDensity : mDp;
        refreshTextPaints();
    }

    void applyConfig(
            boolean percentEnabled,
            float percentSizeSp,
            boolean percentBold,
            String percentPosition,
            float percentOffsetXDp,
            float percentOffsetYDp,
            boolean filenameEnabled,
            float filenameSizeSp,
            boolean filenameBold,
            String filenamePosition,
            float filenameOffsetXDp,
            float filenameOffsetYDp,
            int filenameMaxChars,
            String filenameTruncate) {
        mPercentEnabled = percentEnabled;
        mPercentSizeSp = percentSizeSp;
        mPercentBold = percentBold;
        mPercentPosition = percentPosition;
        mPercentOffsetXDp = percentOffsetXDp;
        mPercentOffsetYDp = percentOffsetYDp;

        mFilenameEnabled = filenameEnabled;
        mFilenameSizeSp = filenameSizeSp;
        mFilenameBold = filenameBold;
        mFilenamePosition = filenamePosition;
        mFilenameOffsetXDp = filenameOffsetXDp;
        mFilenameOffsetYDp = filenameOffsetYDp;
        mFilenameMaxChars = filenameMaxChars;
        mFilenameTruncate = filenameTruncate;
        refreshTextPaints();
    }

    void draw(
            Canvas canvas,
            RectF arcBounds,
            int progressPct,
            int ringColor,
            int opacityPct,
            String filenameHint,
            int downloadCount,
            boolean geometryPreview,
            boolean allowFilename) {
        final float pad = 4f * mDp;
        final int alpha = Math.max(0, Math.min(255, opacityPct * 255 / 100));

        if (mPercentEnabled) {
            final String text = progressPct + "%";
            final float width = mPercentPaint.measureText(text);
            resolvePosition(
                    arcBounds,
                    mPercentPosition,
                    pad,
                    mPercentPaint.getTextSize(),
                    width,
                    true);
            mPercentPaint.setColor(ringColor);
            mPercentPaint.setAlpha(alpha);
            canvas.drawText(
                    text,
                    mPosition[0] + mPercentOffsetXDp * mDp,
                    mPosition[1] + mPercentOffsetYDp * mDp,
                    mPercentPaint);
        }

        final String filename = filenameHint != null
                ? filenameHint
                : geometryPreview ? "EvolutionX-16.0-arm64.zip" : null;
        if (!allowFilename
                || !mFilenameEnabled
                || filename == null
                || (downloadCount > 1 && !geometryPreview)) {
            return;
        }

        final String display = truncate(filename, mFilenameMaxChars, mFilenameTruncate);
        resolvePosition(
                arcBounds,
                mFilenamePosition,
                pad,
                mFilenamePaint.getTextSize(),
                0f,
                false);
        mFilenamePaint.setColor(ringColor);
        mFilenamePaint.setAlpha(alpha);
        canvas.drawText(
                display,
                mPosition[0] + mFilenameOffsetXDp * mDp,
                mPosition[1] + mFilenameOffsetYDp * mDp,
                mFilenamePaint);
    }

    private void refreshTextPaints() {
        mPercentPaint.setTypeface(mPercentBold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mPercentPaint.setTextSize(Math.max(1f, mPercentSizeSp) * mScaledDensity);
        mPercentPaint.setTextAlign(Paint.Align.CENTER);

        mFilenamePaint.setTypeface(mFilenameBold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mFilenamePaint.setTextSize(Math.max(1f, mFilenameSizeSp) * mScaledDensity);
        mFilenamePaint.setTextAlign(Paint.Align.LEFT);
    }

    private void resolvePosition(
            RectF bounds,
            String position,
            float pad,
            float textHeight,
            float textWidth,
            boolean hasTextWidth) {
        switch (position) {
            case "left":
                mPosition[0] = bounds.left - (hasTextWidth ? textWidth / 2f : 0f) - pad;
                mPosition[1] = bounds.centerY() + textHeight / 3f;
                break;
            case "top":
                mPosition[0] = bounds.centerX();
                mPosition[1] = bounds.top - pad;
                break;
            case "bottom":
                mPosition[0] = bounds.centerX();
                mPosition[1] = bounds.bottom + textHeight + pad;
                break;
            case "top_left":
                mPosition[0] = bounds.left - pad;
                mPosition[1] = bounds.top - pad;
                break;
            case "top_right":
                mPosition[0] = bounds.right + pad;
                mPosition[1] = bounds.top - pad;
                break;
            case "bottom_left":
                mPosition[0] = bounds.left - pad;
                mPosition[1] = bounds.bottom + textHeight + pad;
                break;
            case "bottom_right":
                mPosition[0] = bounds.right + pad;
                mPosition[1] = bounds.bottom + textHeight + pad;
                break;
            default:
                mPosition[0] = bounds.right + (hasTextWidth ? textWidth / 2f : 0f) + pad;
                mPosition[1] = bounds.centerY() + textHeight / 3f;
                break;
        }
    }

    private static String truncate(String value, int maxChars, String mode) {
        if (value == null || value.isEmpty() || maxChars <= 0) return "";
        final int count = value.codePointCount(0, value.length());
        if (count <= maxChars) return value;

        final String ellipsis = "\u2026";
        final int available = maxChars - 1;
        if (available <= 0) return ellipsis;

        switch (mode) {
            case "start": {
                final int start = value.offsetByCodePoints(0, count - available);
                return ellipsis + value.substring(start);
            }
            case "end": {
                final int end = value.offsetByCodePoints(0, available);
                return value.substring(0, end) + ellipsis;
            }
            default: {
                final int head = (available + 1) / 2;
                final int tail = available - head;
                final int headEnd = value.offsetByCodePoints(0, head);
                final int tailStart = value.offsetByCodePoints(0, count - tail);
                return value.substring(0, headEnd) + ellipsis + value.substring(tailStart);
            }
        }
    }
}

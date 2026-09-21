/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.cutoutprogress.ring;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Paints the optional burning-fuse endpoint for countdown timers. */
final class TimerFlamePainter {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mPosition = new float[2];
    private final float[] mNormal = new float[2];

    void draw(
            Canvas canvas,
            RingViewRenderer renderer,
            boolean clockwise,
            float timerFraction,
            float flameSizeDp,
            float timerStrokeDp,
            float displayDensity,
            int timerColor,
            int opacityPct,
            float visualPhase) {
        float endpoint = clockwise ? timerFraction : 1f - timerFraction;
        endpoint = endpoint - (float) Math.floor(endpoint);
        if (!renderer.getPointAndOutwardNormal(endpoint, mPosition, mNormal)) return;

        final float flameSize = Math.max(1f, flameSizeDp) * displayDensity;
        final float flicker = 0.88f + 0.12f * (float) Math.sin(visualPhase * 3.1f);
        final float wick = Math.max(
                Math.max(0f, timerStrokeDp) * 0.55f * displayDensity,
                flameSize * 0.22f);
        final float fx = mPosition[0] + mNormal[0] * (wick + flameSize * 0.35f);
        final float fy = mPosition[1] + mNormal[1] * (wick + flameSize * 0.35f);
        final int opacity = Math.max(0, Math.min(100, opacityPct));

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(Math.max(
                1f, Math.max(0f, timerStrokeDp) * 0.45f * displayDensity));
        mPaint.setColor(timerColor);
        mPaint.setAlpha(opacity * 220 / 100);
        canvas.drawLine(
                mPosition[0],
                mPosition[1],
                mPosition[0] + mNormal[0] * wick,
                mPosition[1] + mNormal[1] * wick,
                mPaint);

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(timerColor);
        mPaint.setAlpha(opacity * 190 / 100);
        canvas.drawCircle(fx, fy, flameSize * 0.58f * flicker, mPaint);

        mPaint.setColor(blendColors(timerColor, Color.WHITE, 0.58f));
        mPaint.setAlpha(opacity * 230 / 100);
        canvas.drawCircle(
                fx - mNormal[0] * flameSize * 0.12f,
                fy - mNormal[1] * flameSize * 0.12f,
                flameSize * 0.34f * (1.06f - 0.06f * flicker),
                mPaint);

        mPaint.setColor(Color.WHITE);
        mPaint.setAlpha(opacity * 210 / 100);
        canvas.drawCircle(
                fx - mNormal[0] * flameSize * 0.20f,
                fy - mNormal[1] * flameSize * 0.20f,
                flameSize * 0.13f,
                mPaint);
    }

    private static int blendColors(int from, int to, float amount) {
        final float t = Math.max(0f, Math.min(1f, amount));
        final int a = Math.round(Color.alpha(from)
                + (Color.alpha(to) - Color.alpha(from)) * t);
        final int r = Math.round(Color.red(from)
                + (Color.red(to) - Color.red(from)) * t);
        final int g = Math.round(Color.green(from)
                + (Color.green(to) - Color.green(from)) * t);
        final int b = Math.round(Color.blue(from)
                + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}

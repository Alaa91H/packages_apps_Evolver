/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.cutoutprogress.ring;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Draws the optional procedural music waveform outside the active media ring. */
final class MusicWavePainter {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mPosition = new float[2];
    private final float[] mNormal = new float[2];

    MusicWavePainter() {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void draw(
            Canvas canvas,
            RingViewRenderer renderer,
            int density,
            float amplitudeDp,
            float strokeDp,
            float displayDensity,
            int color,
            int opacityPct,
            float phase) {
        final int sampleCount = Math.max(16, Math.min(96, density));
        final float amplitudeBase = Math.max(0.5f, amplitudeDp) * displayDensity;
        final float pad = (Math.max(0f, strokeDp) * 0.65f + 0.8f) * displayDensity;

        mPaint.setStrokeWidth(Math.max(1f, Math.max(0f, strokeDp) * 0.45f * displayDensity));
        mPaint.setColor(color);
        mPaint.setAlpha(Math.max(0, Math.min(255, opacityPct * 255 / 100)));

        for (int i = 0; i < sampleCount; i++) {
            final float fraction = i / (float) sampleCount;
            if (!renderer.getPointAndOutwardNormal(fraction, mPosition, mNormal)) continue;

            final float t = (float) (Math.PI * 2.0 * fraction);
            float wave = 0.55f
                    + 0.25f * (float) Math.sin(t * 3f + phase)
                    + 0.20f * (float) Math.sin(t * 7f - phase * 1.7f);
            wave = Math.max(0.12f, Math.min(1f, wave));
            final float amplitude = amplitudeBase * wave;

            canvas.drawLine(
                    mPosition[0] + mNormal[0] * pad,
                    mPosition[1] + mNormal[1] * pad,
                    mPosition[0] + mNormal[0] * (pad + amplitude),
                    mPosition[1] + mNormal[1] * (pad + amplitude),
                    mPaint);
        }
    }
}

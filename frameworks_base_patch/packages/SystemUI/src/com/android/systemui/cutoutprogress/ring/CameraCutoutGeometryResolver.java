/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.cutoutprogress.ring;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.PathParser;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a stable camera geometry in current logical-display coordinates.
 *
 * Source priority:
 *  1. SystemUI camera-protection path supplied by the device overlay.
 *  2. DisplayCutout path intersected with the most plausible cutout bound.
 *  3. DisplayCutout bounding rectangle normalized to a compact camera shape.
 *
 * Protection paths are authored in natural/physical display coordinates. They must be scaled by
 * physicalPixelDisplaySizeRatio and rotated into the current logical coordinate space. The logic
 * intentionally mirrors DisplayCutoutBaseView so rotation and resolution changes stay consistent
 * with SystemUI's own screen-decoration geometry.
 */
final class CameraCutoutGeometryResolver {

    static final int SOURCE_NONE = 0;
    static final int SOURCE_CAMERA_PROTECTION = 1;
    static final int SOURCE_DISPLAY_CUTOUT_PATH = 2;
    static final int SOURCE_DISPLAY_CUTOUT_BOUNDS = 3;

    private static final float SAFE_AREA_ASPECT_THRESHOLD = 1.28f;
    private static final float PILL_ASPECT_THRESHOLD = 1.20f;
    private static final float EDGE_TOLERANCE_PX = 2f;

    static final class ResolvedGeometry {
        final Path path;
        final RectF bounds;
        final int source;
        final int rotation;
        final boolean normalizedSafeArea;
        final boolean pillLike;

        ResolvedGeometry(
                Path path,
                RectF bounds,
                int source,
                int rotation,
                boolean normalizedSafeArea,
                boolean pillLike) {
            this.path = new Path(path);
            this.bounds = new RectF(bounds);
            this.source = source;
            this.rotation = rotation;
            this.normalizedSafeArea = normalizedSafeArea;
            this.pillLike = pillLike;
        }
    }

    private static final class Candidate {
        final Path path;
        final RectF bounds;
        final int source;

        Candidate(Path path, RectF bounds, int source) {
            this.path = path;
            this.bounds = bounds;
            this.source = source;
        }
    }

    private final Context mContext;

    CameraCutoutGeometryResolver(Context context) {
        mContext = context;
    }

    ResolvedGeometry resolve(DisplayCutout cutout) {
        if (cutout == null) return null;

        final Display display = mContext.getDisplay();
        final DisplayInfo info = new DisplayInfo();
        if (display != null) {
            display.getDisplayInfo(info);
        }

        final int rotation = resolveRotation(cutout, info);
        final int logicalWidth = resolveLogicalWidth(info);
        final int logicalHeight = resolveLogicalHeight(info);

        Path protection = loadAndTransformProtectionPath(
                display, cutout, info, rotation, logicalWidth, logicalHeight);
        if (isUsable(protection)) {
            RectF bounds = boundsOf(protection);
            boolean normalized = false;
            if (looksLikeEdgeSafeArea(bounds, logicalWidth, logicalHeight)) {
                float diameter = Math.min(bounds.width(), bounds.height());
                if (diameter > 0f) {
                    RectF compact = new RectF(
                            bounds.centerX() - diameter / 2f,
                            bounds.centerY() - diameter / 2f,
                            bounds.centerX() + diameter / 2f,
                            bounds.centerY() + diameter / 2f);
                    Path normalizedPath = new Path();
                    normalizedPath.addOval(compact, Path.Direction.CW);
                    protection = normalizedPath;
                    bounds = compact;
                    normalized = true;
                }
            }
            return buildResolved(
                    protection,
                    bounds,
                    SOURCE_CAMERA_PROTECTION,
                    rotation,
                    normalized,
                    false,
                    logicalWidth,
                    logicalHeight);
        }

        Candidate candidate = chooseDisplayCutoutCandidate(
                cutout, logicalWidth, logicalHeight);
        if (candidate == null || candidate.bounds.isEmpty()) return null;

        RectF bounds = candidate.bounds;
        Path path = candidate.path;
        boolean normalized = false;

        if (looksLikeEdgeSafeArea(bounds, logicalWidth, logicalHeight)) {
            float diameter = Math.min(bounds.width(), bounds.height());
            if (diameter > 0f) {
                RectF compact = new RectF(
                        bounds.centerX() - diameter / 2f,
                        bounds.centerY() - diameter / 2f,
                        bounds.centerX() + diameter / 2f,
                        bounds.centerY() + diameter / 2f);
                Path normalizedPath = new Path();
                normalizedPath.addOval(compact, Path.Direction.CW);
                path = normalizedPath;
                bounds = compact;
                normalized = true;
            }
        }

        return buildResolved(
                path,
                bounds,
                candidate.source,
                rotation,
                normalized,
                false,
                logicalWidth,
                logicalHeight);
    }

    private ResolvedGeometry buildResolved(
            Path path,
            RectF bounds,
            int source,
            int rotation,
            boolean normalized,
            boolean forcePill,
            int logicalWidth,
            int logicalHeight) {
        if (path == null || bounds == null || bounds.isEmpty()) return null;
        float min = Math.min(bounds.width(), bounds.height());
        float max = Math.max(bounds.width(), bounds.height());
        float aspect = min > 0f ? max / min : 1f;
        boolean pill = forcePill || (!normalized && aspect >= PILL_ASPECT_THRESHOLD);

        // Reject obviously invalid geometry that cannot belong to the current logical display.
        if (logicalWidth > 0 && logicalHeight > 0) {
            RectF display = new RectF(0f, 0f, logicalWidth, logicalHeight);
            RectF intersection = new RectF(bounds);
            if (!intersection.intersect(display) || intersection.isEmpty()) {
                return null;
            }
        }

        return new ResolvedGeometry(path, bounds, source, rotation, normalized, pill);
    }

    private Candidate chooseDisplayCutoutCandidate(
            DisplayCutout cutout, int logicalWidth, int logicalHeight) {
        Path allPath = null;
        try {
            allPath = cutout.getCutoutPath();
        } catch (NoSuchMethodError ignored) {
        }

        List<Rect> rects = new ArrayList<>(4);
        addNonEmpty(rects, cutout.getBoundingRectTop());
        addNonEmpty(rects, cutout.getBoundingRectLeft());
        addNonEmpty(rects, cutout.getBoundingRectRight());
        addNonEmpty(rects, cutout.getBoundingRectBottom());

        Candidate best = null;
        double bestScore = Double.MAX_VALUE;

        for (Rect rect : rects) {
            RectF rectF = new RectF(rect);
            Path candidatePath = null;
            RectF candidateBounds = null;
            int source = SOURCE_DISPLAY_CUTOUT_BOUNDS;

            if (isUsable(allPath)) {
                Path clipped = new Path(allPath);
                Path clip = new Path();
                clip.addRect(rectF, Path.Direction.CW);
                if (clipped.op(clip, Path.Op.INTERSECT) && isUsable(clipped)) {
                    RectF clippedBounds = boundsOf(clipped);
                    if (!clippedBounds.isEmpty()) {
                        candidatePath = clipped;
                        candidateBounds = clippedBounds;
                        source = SOURCE_DISPLAY_CUTOUT_PATH;
                    }
                }
            }

            if (candidatePath == null) {
                candidatePath = new Path();
                candidatePath.addRect(rectF, Path.Direction.CW);
                candidateBounds = rectF;
            }

            double score = candidateScore(candidateBounds, logicalWidth, logicalHeight);
            if (score < bestScore) {
                bestScore = score;
                best = new Candidate(candidatePath, candidateBounds, source);
            }
        }

        // Some implementations may expose a path while bounding rects are absent.
        if (best == null && isUsable(allPath)) {
            RectF bounds = boundsOf(allPath);
            if (!bounds.isEmpty()) {
                best = new Candidate(
                        new Path(allPath), bounds, SOURCE_DISPLAY_CUTOUT_PATH);
            }
        }
        return best;
    }

    private static double candidateScore(RectF b, int logicalWidth, int logicalHeight) {
        if (b == null || b.isEmpty()) return Double.MAX_VALUE;

        double area = Math.max(1.0, (double) b.width() * b.height());
        double displayArea = Math.max(
                1.0, (double) Math.max(1, logicalWidth) * Math.max(1, logicalHeight));
        double areaTerm = area / displayArea;

        double min = Math.max(1.0, Math.min(b.width(), b.height()));
        double max = Math.max(b.width(), b.height());
        double aspect = max / min;
        double aspectPenalty = Math.max(0.0, aspect - 1.0);

        // Prefer compact camera-like geometry while still strongly preferring the smaller physical
        // non-functional region when a display reports more than one cutout.
        return areaTerm * 1000.0 + aspectPenalty * 0.08;
    }

    private Path loadAndTransformProtectionPath(
            Display display,
            DisplayCutout cutout,
            DisplayInfo info,
            int rotation,
            int logicalWidth,
            int logicalHeight) {
        String displayUniqueId = display != null ? display.getUniqueId() : null;

        ProtectionSpec inner = new ProtectionSpec(
                safeString(R.string.config_innerBuiltInDisplayCutoutProtection),
                safeString(R.string.config_protectedInnerScreenUniqueId));
        ProtectionSpec outer = new ProtectionSpec(
                safeString(R.string.config_frontBuiltInDisplayCutoutProtection),
                safeString(R.string.config_protectedScreenUniqueId));

        ProtectionSpec selected = selectProtectionSpec(displayUniqueId, inner, outer);
        if (selected == null || selected.pathData.isEmpty()) return null;

        final Path path;
        try {
            path = PathParser.createPathFromPathData(selected.pathData.trim());
        } catch (Throwable ignored) {
            return null;
        }
        if (!isUsable(path)) return null;

        float ratio = 1f;
        try {
            DisplayCutout.CutoutPathParserInfo parserInfo =
                    cutout.getCutoutPathParserInfo();
            float parsed = parserInfo.getPhysicalPixelDisplaySizeRatio();
            if (Float.isFinite(parsed) && parsed > 0f) {
                ratio = parsed;
            }
        } catch (Throwable ignored) {
        }

        Matrix matrix = new Matrix();
        matrix.postScale(ratio, ratio);

        int lw = logicalWidth;
        int lh = logicalHeight;
        if (lw <= 0 || lh <= 0) {
            lw = resolveLogicalWidth(info);
            lh = resolveLogicalHeight(info);
        }
        boolean flipped =
                rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int naturalWidth = flipped ? lh : lw;
        int naturalHeight = flipped ? lw : lh;
        transformPhysicalToLogicalCoordinates(
                rotation, naturalWidth, naturalHeight, matrix);

        path.transform(matrix);
        return path;
    }

    private static final class ProtectionSpec {
        final String pathData;
        final String displayUniqueId;

        ProtectionSpec(String pathData, String displayUniqueId) {
            this.pathData = pathData == null ? "" : pathData.trim();
            this.displayUniqueId =
                    displayUniqueId == null ? "" : displayUniqueId.trim();
        }

        boolean hasPath() {
            return !pathData.isEmpty();
        }

        boolean explicitlyMatches(String currentDisplayUniqueId) {
            return hasPath()
                    && !displayUniqueId.isEmpty()
                    && displayUniqueId.equals(currentDisplayUniqueId);
        }

        boolean isGeneric() {
            return hasPath() && displayUniqueId.isEmpty();
        }
    }

    private static ProtectionSpec selectProtectionSpec(
            String displayUniqueId, ProtectionSpec inner, ProtectionSpec outer) {
        if (displayUniqueId != null && !displayUniqueId.isEmpty()) {
            if (inner.explicitlyMatches(displayUniqueId)) return inner;
            if (outer.explicitlyMatches(displayUniqueId)) return outer;
        }

        // Outer/front is the common single-display case. Inner is only used generically if there
        // is no usable outer protection path.
        if (outer.isGeneric()) return outer;
        if (inner.isGeneric()) return inner;
        return null;
    }

    private String safeString(int resId) {
        try {
            return mContext.getResources().getString(resId);
        } catch (Resources.NotFoundException ignored) {
            return "";
        }
    }

    private int resolveRotation(DisplayCutout cutout, DisplayInfo info) {
        if (info != null
                && info.rotation >= Surface.ROTATION_0
                && info.rotation <= Surface.ROTATION_270) {
            return info.rotation;
        }
        try {
            return cutout.getCutoutPathParserInfo().getRotation();
        } catch (Throwable ignored) {
            return Surface.ROTATION_0;
        }
    }

    private int resolveLogicalWidth(DisplayInfo info) {
        if (info != null && info.logicalWidth > 0) return info.logicalWidth;
        return mContext.getResources().getDisplayMetrics().widthPixels;
    }

    private int resolveLogicalHeight(DisplayInfo info) {
        if (info != null && info.logicalHeight > 0) return info.logicalHeight;
        return mContext.getResources().getDisplayMetrics().heightPixels;
    }

    private static boolean looksLikeEdgeSafeArea(RectF b, int width, int height) {
        if (b == null || b.isEmpty()) return false;
        float min = Math.min(b.width(), b.height());
        float max = Math.max(b.width(), b.height());
        if (min <= 0f || max / min < SAFE_AREA_ASPECT_THRESHOLD) return false;

        boolean touchesLeft = b.left <= EDGE_TOLERANCE_PX;
        boolean touchesTop = b.top <= EDGE_TOLERANCE_PX;
        boolean touchesRight = width > 0 && b.right >= width - EDGE_TOLERANCE_PX;
        boolean touchesBottom = height > 0 && b.bottom >= height - EDGE_TOLERANCE_PX;
        return touchesLeft || touchesTop || touchesRight || touchesBottom;
    }

    private static void addNonEmpty(List<Rect> out, Rect rect) {
        if (rect != null && !rect.isEmpty()) {
            out.add(new Rect(rect));
        }
    }

    private static RectF boundsOf(Path path) {
        RectF bounds = new RectF();
        if (path != null && !path.isEmpty()) {
            path.computeBounds(bounds, true);
        }
        return bounds;
    }

    private static boolean isUsable(Path path) {
        return path != null && !path.isEmpty();
    }

    private static void transformPhysicalToLogicalCoordinates(
            int rotation, int physicalWidth, int physicalHeight, Matrix out) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return;
            case Surface.ROTATION_90:
                out.postRotate(270f);
                out.postTranslate(0f, physicalWidth);
                return;
            case Surface.ROTATION_180:
                out.postRotate(180f);
                out.postTranslate(physicalWidth, physicalHeight);
                return;
            case Surface.ROTATION_270:
                out.postRotate(90f);
                out.postTranslate(physicalHeight, 0f);
                return;
            default:
                return;
        }
    }
}

/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

import com.android.settings.R
import com.android.settingslib.Utils

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class EdgeLightPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var userStrokeWidth = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = baseStrokeWidth()
            invalidate()
        }

    private var userPulseCount = 3
        set(value) {
            field = value.coerceIn(1, 5)
        }

    private var userSpread = 0f
    private var userIntensity = 0f
    private var edgeStyle = STYLE_DEFAULT
    private var animationEffect = EFFECT_NONE
    private var auroraColorMode = AURORA_SINGLE
    private var showTop = false
    private var showSides = true
    private var showBottom = false

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val totalPulseDuration =
        resources.getInteger(R.integer.edge_light_preview_pulse_duration_ms).toLong()
    private val fadeFraction = 0.2f
    private val minSegments = 3

    private val sparkles = mutableListOf<Sparkle>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cornerRadius = 0f
    private var effectAnimator: ValueAnimator? = null
    private var effectProgress = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var rainbowRotation = 0f
    private var settingsObserver: ContentObserver? = null
    private var useRainbowGradient = false
    private var glowBaseColor = Color.WHITE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visibility = View.VISIBLE
        isClickable = false

        cornerRadius = (
            resources.getDimension(R.dimen.edge_light_preview_shell_corner_radius) -
                resources.getDimension(R.dimen.edge_light_preview_shell_content_inset)
        ).coerceAtLeast(0f)

        edgePaint.strokeWidth = baseStrokeWidth()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSettingsObserver()
        post { readSettingsAndStart() }
    }

    override fun onDetachedFromWindow() {
        unregisterSettingsObserver()
        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) updateRainbowGradient()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || !(showTop || showSides || showBottom)) return

        if (animationEffect != EFFECT_BREATHING) {
            edgePaint.strokeWidth = baseStrokeWidth()
        }

        val paths = buildActivePaths()
        if (paths.isEmpty()) return

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                drawPaths(canvas, paths, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffect(canvas, paths)
            EFFECT_SPARKLE -> drawSparkleEffect(canvas, paths)
            EFFECT_CHASE -> drawChaseEffect(canvas, paths)
            EFFECT_COMET -> drawCometEffect(canvas, paths)
            EFFECT_AURORA -> drawAuroraEffect(canvas, paths)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                drawPaths(canvas, paths, edgePaint)
            }
        }

        drawGlow(canvas)
    }

    private fun registerSettingsObserver() {
        if (settingsObserver != null) return
        val resolver = context.contentResolver
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post { readSettingsAndStart() }
            }
        }
        settingsObserver = observer
        WATCHED_KEYS.forEach { key ->
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
    }

    private fun unregisterSettingsObserver() {
        val observer = settingsObserver ?: return
        context.contentResolver.unregisterContentObserver(observer)
        settingsObserver = null
    }

    private fun readSettingsAndStart() {
        val resolver = context.contentResolver
        val colorMode = Settings.System.getStringForUser(
            resolver, "edge_light_color_mode", UserHandle.USER_CURRENT
        ) ?: "accent"
        val customColor = Settings.System.getIntForUser(
            resolver, "edge_light_custom_color", Color.WHITE, UserHandle.USER_CURRENT
        )
        userPulseCount = Settings.System.getIntForUser(
            resolver, "edge_light_pulse_count", 3, UserHandle.USER_CURRENT
        )
        userStrokeWidth = Settings.System.getIntForUser(
            resolver, "edge_light_stroke_width", 8, UserHandle.USER_CURRENT
        )
        edgeStyle = Settings.System.getStringForUser(
            resolver, "edge_light_style", UserHandle.USER_CURRENT
        ) ?: STYLE_DEFAULT
        animationEffect = Settings.System.getStringForUser(
            resolver, "edge_light_animation_effect", UserHandle.USER_CURRENT
        ) ?: EFFECT_NONE
        userSpread = (
            Settings.System.getIntForUser(
                resolver, "edge_light_spread", 0, UserHandle.USER_CURRENT
            ) / 100f
        ).coerceIn(0f, 1f)
        userIntensity = (
            Settings.System.getIntForUser(
                resolver, "edge_light_intensity", 0, UserHandle.USER_CURRENT
            ) / 100f
        ).coerceIn(0f, 1f)

        val frameStyle = isFrameStyle(edgeStyle)
        showTop = readZone("edge_light_top_enabled", frameStyle)
        showSides = readZone("edge_light_sides_enabled", true)
        showBottom = readZone("edge_light_bottom_enabled", frameStyle)
        auroraColorMode = Settings.System.getStringForUser(
            resolver, "edge_light_aurora_color_mode", UserHandle.USER_CURRENT
        ) ?: AURORA_SINGLE

        setPaintColor(resolvePaintColor(colorMode, customColor))

        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
        visibility = View.VISIBLE
        startPulse()
        startEffectAnimation()
        startRainbowAnimation()
        invalidate()
    }

    private fun readZone(key: String, legacyDefault: Boolean): Boolean {
        val value = Settings.System.getStringForUser(
            context.contentResolver, key, UserHandle.USER_CURRENT
        ) ?: return legacyDefault
        return value == "1"
    }

    private fun resolvePaintColor(mode: String, customColor: Int): Int = when (mode) {
        "wallpaper" -> getWallpaperPrimaryColorOrElse(Utils.getColorAccentDefaultColor(context))
        "rainbow" -> COLOR_RAINBOW
        "notification" -> Utils.getColorAccentDefaultColor(context)
        "custom" -> customColor
        "accent" -> Utils.getColorAccentDefaultColor(context)
        else -> Utils.getColorAccentDefaultColor(context)
    }

    private fun getWallpaperPrimaryColorOrElse(default: Int): Int = try {
        val wm = WallpaperManager.getInstance(context) ?: return default
        wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() ?: default
    } catch (_: Exception) {
        default
    }

    private fun setPaintColor(color: Int) {
        if (color != COLOR_RAINBOW) {
            useRainbowGradient = false
            edgePaint.shader = null
            edgePaint.color = color
            edgePaint.alpha = 255
            glowBaseColor = resolveGlowBase(color)
        } else {
            useRainbowGradient = true
            glowBaseColor = RAINBOW[0]
            updateRainbowGradient()
        }
    }

    private fun baseStrokeWidth(): Float =
        userStrokeWidth * resources.displayMetrics.density

    private fun buildActivePaths(): List<Path> {
        val half = edgePaint.strokeWidth / 2f
        val left = half
        val top = half
        val right = width.toFloat() - half
        val bottom = height.toFloat() - half
        if (right <= left || bottom <= top) return emptyList()

        val radius = if (isFrameStyle(edgeStyle)) {
            cornerRadius.coerceAtMost((right - left) / 2f).coerceAtMost((bottom - top) / 2f)
        } else {
            0f
        }
        val paths = mutableListOf<Path>()

        if (showTop) {
            paths += Path().apply {
                moveTo(left + radius, top)
                lineTo(right - radius, top)
            }
        }
        if (showSides) {
            paths += Path().apply {
                moveTo(left, top + radius)
                lineTo(left, bottom - radius)
            }
            paths += Path().apply {
                moveTo(right, top + radius)
                lineTo(right, bottom - radius)
            }
        }
        if (showBottom) {
            paths += Path().apply {
                moveTo(left + radius, bottom)
                lineTo(right - radius, bottom)
            }
        }

        if (radius > 0f) {
            if (showTop && showSides) {
                paths += Path().apply {
                    arcTo(RectF(left, top, left + 2f * radius, top + 2f * radius), 180f, 90f)
                }
                paths += Path().apply {
                    arcTo(RectF(right - 2f * radius, top, right, top + 2f * radius), 270f, 90f)
                }
            }
            if (showBottom && showSides) {
                paths += Path().apply {
                    arcTo(RectF(left, bottom - 2f * radius, left + 2f * radius, bottom), 90f, 90f)
                }
                paths += Path().apply {
                    arcTo(RectF(right - 2f * radius, bottom - 2f * radius, right, bottom), 0f, 90f)
                }
            }
        }
        return paths
    }

    private fun drawPaths(canvas: Canvas, paths: List<Path>, paint: Paint) {
        paths.forEach { canvas.drawPath(it, paint) }
    }

    private fun applyBreathingEffect() {
        val pulse = (sin(2.0 * PI * effectProgress).toFloat() + 1f) / 2f
        edgePaint.alpha = (155 + 100f * pulse).toInt().coerceIn(0, 255)
        edgePaint.strokeWidth = baseStrokeWidth() * (0.7f + pulse * 0.6f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null
        val amplitude = baseStrokeWidth() * 0.75f
        paths.forEachIndexed { index, path ->
            canvas.drawPath(
                buildWavyPath(path, amplitude, effectProgress + index * 0.13f),
                edgePaint,
            )
        }
    }

    private fun drawSparkleEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 90
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        sparkles.removeAll { it.lifetime <= 0f }
        if (sparkles.size < 20 && Random.nextFloat() < 0.36f) {
            randomPointOnPaths(paths)?.let { (x, y) ->
                sparkles += Sparkle(
                    x, y, 1f, baseStrokeWidth() * (1.5f + Random.nextFloat() * 2.5f)
                )
            }
        }

        val p = Paint(edgePaint).apply {
            shader = null
            strokeCap = Paint.Cap.ROUND
            color = effectiveGlowBaseColor()
        }
        sparkles.forEach {
            it.lifetime -= 0.035f
            p.alpha = (it.lifetime * 255f).toInt().coerceIn(0, 255)
            p.strokeWidth = it.maxSize * sin(it.lifetime.toDouble() * PI).toFloat()
            canvas.drawPoint(it.x, it.y, p)
        }
    }

    private fun drawChaseEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 48
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        paths.forEach { path ->
            val measure = PathMeasure(path, false)
            val length = measure.length
            if (length <= 0f) return@forEach
            val trail = length * 0.18f
            repeat(3) { i ->
                val center = ((effectProgress + i / 3f) % 1f) * length
                val segment = Path()
                measure.getSegment(
                    (center - trail).coerceAtLeast(0f),
                    (center + trail).coerceAtMost(length),
                    segment,
                    true,
                )
                canvas.drawPath(segment, Paint(edgePaint).apply {
                    alpha = 255
                    strokeWidth = baseStrokeWidth() * 1.15f
                })
            }
        }
    }

    private fun drawCometEffect(canvas: Canvas, paths: List<Path>) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        drawPaths(canvas, paths, edgePaint)

        paths.forEach { path ->
            val measure = PathMeasure(path, false)
            val length = measure.length
            if (length <= 0f) return@forEach
            val head = effectProgress * length
            val segment = Path()
            measure.getSegment(
                (head - length * 0.30f).coerceAtLeast(0f),
                head,
                segment,
                true,
            )
            canvas.drawPath(segment, Paint(edgePaint).apply {
                alpha = 255
                strokeWidth = baseStrokeWidth() * 1.55f
            })
        }
    }

    private fun drawAuroraEffect(canvas: Canvas, paths: List<Path>) {
        val base = baseStrokeWidth()
        val multi = auroraColorMode == AURORA_MULTI

        paths.forEachIndexed { index, path ->
            val primary = buildWavyPath(
                path, base * 1.15f, effectProgress + index * 0.17f, highQuality = true
            )
            val secondary = buildWavyPath(
                path,
                base * 0.55f,
                effectProgress * 0.72f + 0.33f + index * 0.11f,
                highQuality = true,
            )
            drawAuroraLayers(canvas, primary, base, multi, 1f)
            drawAuroraLayers(canvas, secondary, base * 0.72f, multi, 0.68f)
        }
    }

    private fun drawAuroraLayers(
        canvas: Canvas,
        path: Path,
        baseWidth: Float,
        multi: Boolean,
        alphaScale: Float,
    ) {
        val shader = if (multi) auroraShader() else null
        val color = effectiveGlowBaseColor()
        val widths = floatArrayOf(6.2f, 4.1f, 2.35f, 1f)
        val alphas = intArrayOf(22, 42, 92, 238)
        val blur = floatArrayOf(2.8f, 1.8f, 0.8f, 0f)

        widths.indices.forEach { i ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = baseWidth * widths[i]
                alpha = (alphas[i] * alphaScale).toInt().coerceIn(0, 255)
                if (shader != null) this.shader = shader else this.color = color
                if (blur[i] > 0f) {
                    maskFilter = BlurMaskFilter(
                        (baseWidth * blur[i]).coerceAtLeast(1f),
                        BlurMaskFilter.Blur.NORMAL,
                    )
                }
            }
            canvas.drawPath(path, p)
        }
    }

    private fun buildWavyPath(
        source: Path,
        amplitude: Float,
        phase: Float,
        highQuality: Boolean = false,
    ): Path {
        val measure = PathMeasure(source, false)
        val length = measure.length
        if (length <= 0f) return Path(source)

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val samples = if (highQuality) {
            max(160, (length / (density * 1.75f)).toInt()).coerceAtMost(480)
        } else {
            max(48, (length / (density * 5f)).toInt()).coerceAtMost(180)
        }

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val out = Path()
        for (i in 0..samples) {
            val t = i / samples.toFloat()
            measure.getPosTan(length * t, pos, tan)
            val harmonic =
                sin((t * 3.0 + phase * 2.0) * PI).toFloat() * 0.72f +
                sin((t * 7.0 - phase * 1.35) * PI).toFloat() * 0.28f
            val offset = harmonic * amplitude
            val x = pos[0] - tan[1] * offset
            val y = pos[1] + tan[0] * offset
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        return out
    }

    private fun randomPointOnPaths(paths: List<Path>): Pair<Float, Float>? {
        val measured = paths.map { PathMeasure(it, false) }.filter { it.length > 0f }
        if (measured.isEmpty()) return null
        val total = measured.sumOf { it.length.toDouble() }.toFloat()
        var target = Random.nextFloat() * total
        measured.forEach { measure ->
            if (target <= measure.length) {
                val pos = FloatArray(2)
                measure.getPosTan(target, pos, null)
                return pos[0] to pos[1]
            }
            target -= measure.length
        }
        return null
    }

    private fun drawGlow(canvas: Canvas) {
        if (userIntensity <= 0f || userSpread <= 0f) return
        val base = effectiveGlowBaseColor() and 0x00FFFFFF
        val a = (alpha.coerceIn(0f, 1f) * userIntensity).coerceIn(0f, 1f)
        val colors = intArrayOf(
            withAlpha(base, a),
            withAlpha(base, a * 0.62f),
            withAlpha(base, a * 0.28f),
            withAlpha(base, a * 0.09f),
            withAlpha(base, 0f),
        )
        val stops = floatArrayOf(0f, 0.12f, 0.34f, 0.68f, 1f)
        val sideSpread = (width * userSpread).coerceAtMost(width * 0.5f)
        val verticalSpread = (height * userSpread).coerceAtMost(height * 0.35f)

        if (showSides && sideSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, 0f, sideSpread, 0f, colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, sideSpread, height.toFloat(), glowPaint)
            glowPaint.shader = LinearGradient(
                width.toFloat(), 0f, width - sideSpread, 0f,
                colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(width - sideSpread, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        if (showTop && verticalSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, 0f, 0f, verticalSpread, colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), verticalSpread, glowPaint)
        }
        if (showBottom && verticalSpread > 0f) {
            glowPaint.shader = LinearGradient(
                0f, height.toFloat(), 0f, height - verticalSpread,
                colors, stops, Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                0f, height - verticalSpread, width.toFloat(), height.toFloat(), glowPaint
            )
        }
        glowPaint.shader = null
    }

    private fun withAlpha(base: Int, value: Float): Int {
        val a = (value * 255f).toInt().coerceIn(0, 255)
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    private fun resolveGlowBase(color: Int): Int {
        if (color == Color.TRANSPARENT || color == 0) return Color.WHITE
        val opaque = color or 0xFF000000.toInt()
        return if (opaque == Color.BLACK) Color.WHITE else opaque
    }

    private fun rainbowGlowColor(): Int {
        val index = ((rainbowRotation / 360f) * (RAINBOW.size - 1)).toInt()
            .coerceIn(0, RAINBOW.size - 2)
        return RAINBOW[index]
    }

    private fun effectiveGlowBaseColor(): Int =
        if (useRainbowGradient) rainbowGlowColor() else glowBaseColor

    private fun auroraShader(): Shader {
        val matrix = Matrix().apply {
            postRotate(effectProgress * 360f, width / 2f, height / 2f)
        }
        return SweepGradient(width / 2f, height / 2f, AURORA_COLORS, null).apply {
            setLocalMatrix(matrix)
        }
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { applyRainbowGradient() }
        } else {
            applyRainbowGradient()
        }
    }

    private fun applyRainbowGradient() {
        if (width <= 0 || height <= 0) return
        edgePaint.shader = if (isFrameStyle(edgeStyle) || showTop || showBottom) {
            val matrix = Matrix().apply {
                postRotate(rainbowRotation, width / 2f, height / 2f)
            }
            SweepGradient(width / 2f, height / 2f, RAINBOW, null).apply {
                setLocalMatrix(matrix)
            }
        } else {
            val offset = (rainbowRotation / 360f) * height
            LinearGradient(
                0f, -offset, 0f, height.toFloat() - offset,
                RAINBOW, null, Shader.TileMode.REPEAT
            )
        }
    }

    private fun startPulse() {
        visibility = View.VISIBLE
        alpha = 0f
        val totalSegments = max(userPulseCount, minSegments)
        val active = BooleanArray(totalSegments)
        for (i in 0 until userPulseCount) {
            val idx = ((i + 0.5f) * totalSegments / userPulseCount).toInt()
                .coerceIn(0, totalSegments - 1)
            active[idx] = true
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, totalSegments.toFloat()).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                val idx = v.toInt().coerceIn(0, totalSegments - 1)
                val local = v - idx
                alpha = if (active[idx]) {
                    when {
                        local < fadeFraction -> local / fadeFraction
                        local > 1f - fadeFraction -> (1f - local) / fadeFraction
                        else -> 1f
                    }
                } else {
                    0f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    pulseAnimator = null
                }
            })
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun startEffectAnimation() {
        if (animationEffect == EFFECT_NONE || effectAnimator?.isRunning == true) return
        val durationMs = when (animationEffect) {
            EFFECT_CHASE -> 2500L
            EFFECT_BREATHING -> 3000L
            EFFECT_SPARKLE -> 100L
            EFFECT_AURORA -> 4200L
            EFFECT_COMET, EFFECT_WAVE -> 2000L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                effectProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopEffectAnimation() {
        effectAnimator?.cancel()
        effectAnimator = null
        effectProgress = 0f
        sparkles.clear()
    }

    private fun startRainbowAnimation() {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (animationEffect in MOVING_EFFECTS) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                rainbowRotation = it.animatedValue as Float
                glowBaseColor = rainbowGlowColor()
                applyRainbowGradient()
                invalidate()
            }
            start()
        }
    }

    private fun stopRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        rainbowRotation = 0f
    }

    private data class Sparkle(
        val x: Float,
        val y: Float,
        var lifetime: Float,
        val maxSize: Float,
    )

    companion object {
        private const val STYLE_DEFAULT = "default"
        private const val EFFECT_NONE = "none"
        private const val EFFECT_BREATHING = "breathing"
        private const val EFFECT_WAVE = "wave"
        private const val EFFECT_SPARKLE = "sparkle"
        private const val EFFECT_CHASE = "chase"
        private const val EFFECT_COMET = "comet"
        private const val EFFECT_AURORA = "aurora"
        private const val AURORA_SINGLE = "single"
        private const val AURORA_MULTI = "multi"
        private const val COLOR_RAINBOW = -1

        private val WATCHED_KEYS = arrayOf(
            "edge_light_color_mode",
            "edge_light_custom_color",
            "edge_light_pulse_count",
            "edge_light_stroke_width",
            "edge_light_style",
            "edge_light_animation_effect",
            "edge_light_spread",
            "edge_light_intensity",
            "edge_light_top_enabled",
            "edge_light_sides_enabled",
            "edge_light_bottom_enabled",
            "edge_light_aurora_color_mode",
        )

        private val MOVING_EFFECTS = arrayOf(
            EFFECT_WAVE,
            EFFECT_SPARKLE,
            EFFECT_CHASE,
            EFFECT_COMET,
            EFFECT_AURORA,
        )

        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFF7F00.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00C8FF.toInt(),
            0xFF4050FF.toInt(),
            0xFF9B32FF.toInt(),
            0xFFFF2DAA.toInt(),
            0xFFFF0000.toInt(),
        )

        private val AURORA_COLORS = intArrayOf(
            0xFF2DFFDB.toInt(),
            0xFF22B8FF.toInt(),
            0xFF635BFF.toInt(),
            0xFFB64CFF.toInt(),
            0xFFFF4FD8.toInt(),
            0xFF6DFF9A.toInt(),
            0xFF2DFFDB.toInt(),
        )

        private fun isFrameStyle(style: String): Boolean {
            val s = style.trim()
            return s.equals("rounded", ignoreCase = true) ||
                s.equals("frame", ignoreCase = true)
        }
    }
}

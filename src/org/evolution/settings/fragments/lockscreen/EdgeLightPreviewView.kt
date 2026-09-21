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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.PorterDuff
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

import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class EdgeLightPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = field * resources.displayMetrics.density
            invalidate()
        }

    var edgeStyle: String = "default"
        set(value) {
            field = value
            if (useRainbowGradient) updateRainbowGradient()
            invalidate()
        }

    private var animationEffect: String = "none"
    private var positionTop: Boolean = false
    private var positionSides: Boolean = true
    private var positionBottom: Boolean = false
    private var auroraColorMode: String = "single"
    private var auroraBaseColor: Int = Utils.getColorAccentDefaultColor(context)

    private var userPulseCount: Int = 3
        set(value) { field = value.coerceIn(1, 5) }

    private val edgePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val auroraPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    private val edgeClipPath = Path()

    private val totalPulseDuration: Long =
        resources.getInteger(R.integer.edge_light_preview_pulse_duration_ms).toLong()

    private val fadeFraction: Float = 0.2f
    private val minSegments: Int = 3

    private val sparkles: MutableList<Sparkle> = mutableListOf()
    private val roundedPath: Path = Path()
    private val roundedRect: RectF = RectF()
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var cornerRadius: Float = 0f
    private var pathLength: Float = 0f

    private var effectAnimator: ValueAnimator? = null
    private var effectProgress: Float = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var rainbowRotation: Float = 0f

    private var settingsObserver: ContentObserver? = null
    private var useRainbowGradient: Boolean = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visibility = View.VISIBLE
        isClickable = false

        cornerRadius = (
            resources.getDimension(R.dimen.edge_light_preview_shell_corner_radius) -
                resources.getDimension(R.dimen.edge_light_preview_shell_content_inset)
        ).coerceAtLeast(0f)

        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (!positionTop && !positionSides && !positionBottom) return
        if (animationEffect != "breathing") {
            edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
        }
        if (animationEffect == "aurora") {
            drawAurora(canvas)
            return
        }

        val save = canvas.save()
        clipToEnabledEdges(canvas)
        if (isFrameStyle(edgeStyle) && positionTop && positionSides && positionBottom) {
            drawRoundedEdges(canvas)
        } else {
            drawDefaultEdges(canvas)
        }
        canvas.restoreToCount(save)
    }

    private fun clipToEnabledEdges(canvas: Canvas) {
        edgeClipPath.reset()
        val density = resources.displayMetrics.density
        val minBand = edgePaint.strokeWidth * 4f + 8f * density
        val horizontalBand = max(minBand, height * 0.18f).coerceAtMost(height.toFloat())
        val verticalBand = max(minBand, width * 0.18f).coerceAtMost(width.toFloat())

        if (positionTop) {
            edgeClipPath.addRect(0f, 0f, width.toFloat(), horizontalBand, Path.Direction.CW)
        }
        if (positionBottom) {
            edgeClipPath.addRect(
                0f, height - horizontalBand, width.toFloat(), height.toFloat(), Path.Direction.CW
            )
        }
        if (positionSides) {
            edgeClipPath.addRect(0f, 0f, verticalBand, height.toFloat(), Path.Direction.CW)
            edgeClipPath.addRect(
                width - verticalBand, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW
            )
        }
        canvas.clipPath(edgeClipPath)
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
        for (key in WATCHED_KEYS) {
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
        var colorMode = "accent"
        var customColor = -1
        var pulseCount = 3
        var strokeWidth = 8
        var style = "default"
        var effect = "none"
        var top = false
        var sides = true
        var bottom = false
        var auroraMode = "single"

        try {
            Settings.System.getStringForUser(
                resolver, "edge_light_color_mode", UserHandle.USER_CURRENT
            )?.let { colorMode = it }
        } catch (_: Exception) {}
        try {
            customColor = Settings.System.getIntForUser(
                resolver, "edge_light_custom_color", -1, UserHandle.USER_CURRENT
            )
        } catch (_: Exception) {}
        try {
            pulseCount = Settings.System.getIntForUser(
                resolver, "edge_light_pulse_count", 3, UserHandle.USER_CURRENT
            )
        } catch (_: Exception) {}
        try {
            strokeWidth = Settings.System.getIntForUser(
                resolver, "edge_light_stroke_width", 8, UserHandle.USER_CURRENT
            )
        } catch (_: Exception) {}
        try {
            Settings.System.getStringForUser(
                resolver, "edge_light_style", UserHandle.USER_CURRENT
            )?.let { style = it }
        } catch (_: Exception) {}
        try {
            Settings.System.getStringForUser(
                resolver, "edge_light_animation_effect", UserHandle.USER_CURRENT
            )?.let { effect = it }
        } catch (_: Exception) {}
        try {
            top = Settings.System.getIntForUser(
                resolver, "edge_light_position_top", 0, UserHandle.USER_CURRENT
            ) == 1
        } catch (_: Exception) {}
        try {
            sides = Settings.System.getIntForUser(
                resolver, "edge_light_position_sides", 1, UserHandle.USER_CURRENT
            ) == 1
        } catch (_: Exception) {}
        try {
            bottom = Settings.System.getIntForUser(
                resolver, "edge_light_position_bottom", 0, UserHandle.USER_CURRENT
            ) == 1
        } catch (_: Exception) {}
        try {
            Settings.System.getStringForUser(
                resolver, "edge_light_aurora_color_mode", UserHandle.USER_CURRENT
            )?.let { auroraMode = it }
        } catch (_: Exception) {}

        userPulseCount = pulseCount
        userStrokeWidth = strokeWidth
        edgeStyle = style
        animationEffect = effect
        positionTop = top
        positionSides = sides
        positionBottom = bottom
        auroraColorMode = if (auroraMode == "multicolor") "multicolor" else "single"
        setPaintColor(resolvePaintColor(colorMode, customColor))

        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
        visibility = View.VISIBLE
        startPulse()
        startEffectAnimation()
        startRainbowAnimation()
    }

    private fun resolvePaintColor(mode: String, customColor: Int): Int = when (mode) {
        "wallpaper" -> getWallpaperPrimaryColorOrElse(Utils.getColorAccentDefaultColor(context))
        "rainbow" -> -1
        "notification" -> Utils.getColorAccentDefaultColor(context)
        "custom" -> customColor
        "accent" -> Utils.getColorAccentDefaultColor(context)
        else -> Utils.getColorAccentDefaultColor(context)
    }

    private fun getWallpaperPrimaryColorOrElse(default: Int): Int = try {
        val wm = WallpaperManager.getInstance(context) ?: return default
        val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return default
        colors.primaryColor.toArgb()
    } catch (_: Exception) {
        default
    }

    private fun setPaintColor(color: Int) {
        if (color != -1) {
            useRainbowGradient = false
            edgePaint.shader = null
            edgePaint.color = color
            edgePaint.alpha = 255
            auroraBaseColor = color
        } else {
            useRainbowGradient = true
            auroraBaseColor = Utils.getColorAccentDefaultColor(context)
            updateRainbowGradient()
        }
        invalidate()
    }

    private fun defaultVerticalEdgeY(strokeHalf: Float): Pair<Float, Float> {
        val h = height.toFloat()
        if (h <= 0f) return 0f to 0f
        val top = strokeHalf
        val bottom = h - strokeHalf
        if (bottom > top) return top to bottom
        val first = (0.12f * h).coerceAtLeast(strokeHalf + 1f)
        val second = first.coerceAtMost(0.45f * h)
        val third = (h - first).coerceAtLeast(0.55f * h)
        return if (third > second) second to third else (0.2f * h) to (0.8f * h)
    }

    private fun drawDefaultEdges(canvas: Canvas) {
        edgePaint.strokeCap =
            if (isFrameStyle(edgeStyle)) Paint.Cap.ROUND else Paint.Cap.BUTT
        when (animationEffect) {
            "breathing" -> {
                applyBreathingEffect()
                drawSelectedBaseLines(canvas, edgePaint)
            }
            "wave" -> drawWaveEffect(canvas)
            "chase" -> drawChaseEffect(canvas)
            "sparkle" -> drawSparkleEffect(canvas)
            "comet" -> drawCometEffect(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                drawSelectedBaseLines(canvas, edgePaint)
            }
        }
    }

    private fun drawSelectedBaseLines(canvas: Canvas, paint: Paint) {
        val halfStroke = paint.strokeWidth / 2f
        val (top, bottom) = defaultVerticalEdgeY(halfStroke)
        if (positionSides) {
            canvas.drawLine(halfStroke, top, halfStroke, bottom, paint)
            canvas.drawLine(width - halfStroke, top, width - halfStroke, bottom, paint)
        }
        if (positionTop) {
            canvas.drawLine(0f, halfStroke, width.toFloat(), halfStroke, paint)
        }
        if (positionBottom) {
            canvas.drawLine(0f, height - halfStroke, width.toFloat(), height - halfStroke, paint)
        }
    }

    private fun drawRoundedEdges(canvas: Canvas) {
        val strokeHalf = edgePaint.strokeWidth / 2f
        edgePaint.strokeCap = Paint.Cap.ROUND
        roundedRect.set(
            strokeHalf,
            strokeHalf,
            width - strokeHalf,
            height - strokeHalf,
        )
        roundedPath.reset()
        val radius = if (isFrameStyle(edgeStyle)) cornerRadius else 0f
        roundedPath.addRoundRect(
            roundedRect, radius, radius, Path.Direction.CW
        )
        when (animationEffect) {
            "breathing" -> {
                applyBreathingEffect()
                canvas.drawPath(roundedPath, edgePaint)
            }
            "wave" -> drawWaveEffectRounded(canvas)
            "chase" -> drawChaseEffectRounded(canvas)
            "sparkle" -> drawSparkleEffectRounded(canvas)
            "comet" -> drawCometEffectRounded(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawPath(roundedPath, edgePaint)
            }
        }
    }

    private fun applyBreathingEffect() {
        val pulse =
            (sin(2.0 * Math.PI * effectProgress).toFloat() + 1f) / 2f
        edgePaint.alpha = (155 + (100f * pulse).toInt()).coerceIn(0, 255)
        val baseStroke = userStrokeWidth * resources.displayMetrics.density
        edgePaint.strokeWidth = baseStroke * (pulse * 0.6f + 0.7f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas) {
        val strokeHalf = edgePaint.strokeWidth / 2f
        val segments = 50
        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        if (positionSides) {
            val (top, bottom) = defaultVerticalEdgeY(strokeHalf)
            val length = bottom - top
            val amplitude = width * 0.05f
            for (index in 0 until segments) {
                val t1 = index / segments.toFloat()
                val t2 = (index + 1) / segments.toFloat()
                val y1 = top + t1 * length
                val y2 = top + t2 * length
                val dx1 = sin((effectProgress + t1) * 2.0 * Math.PI).toFloat() * amplitude
                val dx2 = sin((effectProgress + t2) * 2.0 * Math.PI).toFloat() * amplitude
                canvas.drawLine(strokeHalf + dx1, y1, strokeHalf + dx2, y2, edgePaint)
                canvas.drawLine(
                    width - strokeHalf + dx1, y1,
                    width - strokeHalf + dx2, y2,
                    edgePaint,
                )
            }
        }

        if (positionTop || positionBottom) {
            val amplitude = height * 0.025f
            for (index in 0 until segments) {
                val t1 = index / segments.toFloat()
                val t2 = (index + 1) / segments.toFloat()
                val x1 = t1 * width
                val x2 = t2 * width
                val dy1 = sin((effectProgress + t1) * 2.0 * Math.PI).toFloat() * amplitude
                val dy2 = sin((effectProgress + t2) * 2.0 * Math.PI).toFloat() * amplitude
                if (positionTop) {
                    canvas.drawLine(x1, strokeHalf + dy1, x2, strokeHalf + dy2, edgePaint)
                }
                if (positionBottom) {
                    canvas.drawLine(
                        x1, height - strokeHalf + dy1,
                        x2, height - strokeHalf + dy2,
                        edgePaint,
                    )
                }
            }
        }
    }

    private fun drawWaveEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null
        val pm = PathMeasure(roundedPath, false)
        pathLength = pm.length
        val ampl = edgePaint.strokeWidth * 0.5f
        val deflected = Path()
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0..100) {
            val t = i / 100f
            pm.getPosTan(pathLength * t, pos, tan)
            val sinVal = sin((effectProgress * 2f + t * 4f) * Math.PI).toFloat() * ampl
            val px = pos[0] - tan[1] * sinVal
            val py = pos[1] + tan[0] * sinVal
            if (i == 0) deflected.moveTo(px, py) else deflected.lineTo(px, py)
        }
        canvas.drawPath(deflected, edgePaint)
    }

    private fun drawChaseEffect(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        val strokeHalf = edgePaint.strokeWidth / 2f
        val baseColor = edgePaint.color
        val transparent = baseColor and 0x00FFFFFF

        if (positionSides) {
            val (top, bottom) = defaultVerticalEdgeY(strokeHalf)
            val length = bottom - top
            val trail = 0.15f * length
            for (index in 0 until 3) {
                val pos = top + ((effectProgress + index / 3f) % 1f) * length
                val gradient = LinearGradient(
                    0f, pos - trail, 0f, pos + trail,
                    intArrayOf(transparent, baseColor, transparent),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                val pulsePaint = Paint(edgePaint).apply {
                    shader = gradient
                    alpha = 255
                }
                val y1 = (pos - trail).coerceAtLeast(top)
                val y2 = (pos + trail).coerceAtMost(bottom)
                canvas.drawLine(strokeHalf, y1, strokeHalf, y2, pulsePaint)
                canvas.drawLine(width - strokeHalf, y1, width - strokeHalf, y2, pulsePaint)
            }
        }

        if (positionTop || positionBottom) {
            val trail = 0.15f * width
            for (index in 0 until 3) {
                val pos = ((effectProgress + index / 3f) % 1f) * width
                val gradient = LinearGradient(
                    pos - trail, 0f, pos + trail, 0f,
                    intArrayOf(transparent, baseColor, transparent),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                val pulsePaint = Paint(edgePaint).apply {
                    shader = gradient
                    alpha = 255
                }
                val x1 = (pos - trail).coerceAtLeast(0f)
                val x2 = (pos + trail).coerceAtMost(width.toFloat())
                if (positionTop) canvas.drawLine(x1, strokeHalf, x2, strokeHalf, pulsePaint)
                if (positionBottom) {
                    canvas.drawLine(
                        x1, height - strokeHalf, x2, height - strokeHalf, pulsePaint
                    )
                }
            }
        }
    }

    private fun drawChaseEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val pm = PathMeasure(roundedPath, false)
        pathLength = pm.length
        val trail = 0.15f * pathLength
        for (i in 0 until 3) {
            val pos = ((effectProgress + i / 3f) % 1f) * pathLength
            val seg = Path()
            pm.getSegment(
                (pos - trail).coerceAtLeast(0f),
                (pos + trail).coerceAtMost(pathLength),
                seg,
                true,
            )
            val pulsePaint = Paint(edgePaint).apply { alpha = 255 }
            canvas.drawPath(seg, pulsePaint)
        }
    }

    private fun drawCometEffect(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        val strokeHalf = edgePaint.strokeWidth / 2f
        val baseColor = edgePaint.color
        val transparent = baseColor and 0x00FFFFFF

        if (positionSides) {
            val (top, bottom) = defaultVerticalEdgeY(strokeHalf)
            val length = bottom - top
            val trail = 0.25f * length
            val head = top + effectProgress * length
            val tailY = (head - trail).coerceAtLeast(top)
            val gradient = LinearGradient(
                0f, tailY, 0f, head,
                intArrayOf(transparent, baseColor),
                null,
                Shader.TileMode.CLAMP,
            )
            val cometPaint = Paint(edgePaint).apply {
                shader = gradient
                alpha = 255
                strokeWidth = edgePaint.strokeWidth * 1.5f
            }
            canvas.drawLine(strokeHalf, tailY, strokeHalf, head, cometPaint)
            canvas.drawLine(width - strokeHalf, tailY, width - strokeHalf, head, cometPaint)
        }

        if (positionTop || positionBottom) {
            val trail = 0.25f * width
            val head = effectProgress * width
            val tailX = (head - trail).coerceAtLeast(0f)
            val gradient = LinearGradient(
                tailX, 0f, head, 0f,
                intArrayOf(transparent, baseColor),
                null,
                Shader.TileMode.CLAMP,
            )
            val cometPaint = Paint(edgePaint).apply {
                shader = gradient
                alpha = 255
                strokeWidth = edgePaint.strokeWidth * 1.5f
            }
            if (positionTop) canvas.drawLine(tailX, strokeHalf, head, strokeHalf, cometPaint)
            if (positionBottom) {
                canvas.drawLine(
                    tailX, height - strokeHalf, head, height - strokeHalf, cometPaint
                )
            }
        }
    }

    private fun drawCometEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val pm = PathMeasure(roundedPath, false)
        pathLength = pm.length
        val trail = 0.2f * pathLength
        val head = effectProgress * pathLength
        val seg = Path()
        pm.getSegment((head - trail).coerceAtLeast(0f), head, seg, true)
        val cometPaint = Paint(edgePaint).apply {
            alpha = 255
            strokeWidth = edgePaint.strokeWidth * 1.5f
        }
        canvas.drawPath(seg, cometPaint)
    }

    private fun drawSparkleEffect(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        drawSelectedBaseLines(canvas, edgePaint)

        sparkles.removeAll { it.lifetime <= 0f }

        val activeEdges = mutableListOf<Int>()
        if (positionSides) {
            activeEdges += EDGE_LEFT
            activeEdges += EDGE_RIGHT
        }
        if (positionTop) activeEdges += EDGE_TOP
        if (positionBottom) activeEdges += EDGE_BOTTOM

        if (activeEdges.isNotEmpty() && sparkles.size < 18 && Random.nextFloat() < 0.3f) {
            val edge = activeEdges[Random.nextInt(activeEdges.size)]
            val strokeHalf = edgePaint.strokeWidth / 2f
            val (top, bottom) = defaultVerticalEdgeY(strokeHalf)
            val x: Float
            val y: Float
            when (edge) {
                EDGE_LEFT -> {
                    x = strokeHalf
                    y = top + Random.nextFloat() * (bottom - top)
                }
                EDGE_RIGHT -> {
                    x = width - strokeHalf
                    y = top + Random.nextFloat() * (bottom - top)
                }
                EDGE_TOP -> {
                    x = Random.nextFloat() * width
                    y = strokeHalf
                }
                else -> {
                    x = Random.nextFloat() * width
                    y = height - strokeHalf
                }
            }
            val maxSize = edgePaint.strokeWidth * (Random.nextFloat() * 2f + 2f)
            sparkles.add(Sparkle(x, y, 1f, maxSize))
        }

        val sparklePaint = Paint(edgePaint).apply {
            strokeCap = Paint.Cap.ROUND
        }
        for (s in sparkles) {
            s.lifetime -= 0.03f
            val alpha = (s.lifetime * 255f).toInt().coerceIn(0, 255)
            val sw = s.maxSize * sin(s.lifetime.toDouble() * Math.PI).toFloat()
            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = sw
            canvas.drawPoint(s.x, s.y, sparklePaint)
        }
    }

    private fun drawSparkleEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val pm = PathMeasure(roundedPath, false)
        pathLength = pm.length

        sparkles.removeAll { it.lifetime <= 0f }
        if (sparkles.size < 20 && Random.nextFloat() < 0.3f) {
            val pos = Random.nextFloat() * pathLength
            val xy = FloatArray(2)
            pm.getPosTan(pos, xy, null)
            val maxSize = edgePaint.strokeWidth * (Random.nextFloat() * 2f + 2f)
            sparkles.add(Sparkle(xy[0], xy[1], 1f, maxSize))
        }

        val sparklePaint = Paint(edgePaint).apply {
            strokeCap = Paint.Cap.ROUND
        }
        for (s in sparkles) {
            s.lifetime -= 0.03f
            val alpha = (s.lifetime * 255f).toInt().coerceIn(0, 255)
            val sw = s.maxSize * sin(s.lifetime.toDouble() * Math.PI).toFloat()
            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = sw
            canvas.drawPoint(s.x, s.y, sparklePaint)
        }
    }

    private fun drawAurora(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val baseStroke = userStrokeWidth * density
        val sideSpread = max(baseStroke * 3f, width * 0.22f).coerceAtMost(width * 0.55f)
        val horizontalSpread =
            max(baseStroke * 3f, height * 0.12f).coerceAtMost(height * 0.32f)

        if (positionSides) {
            drawAuroraVerticalBand(canvas, true, sideSpread)
            drawAuroraVerticalBand(canvas, false, sideSpread)
        }
        if (positionTop) drawAuroraHorizontalBand(canvas, true, horizontalSpread)
        if (positionBottom) drawAuroraHorizontalBand(canvas, false, horizontalSpread)

        drawAuroraCore(canvas, baseStroke)
    }

    private fun drawAuroraVerticalBand(canvas: Canvas, left: Boolean, spread: Float) {
        val colorShader = auroraLongitudinalShader(false, height.toFloat(), effectProgress)
        val fadeColors = if (left) {
            intArrayOf(
                alphaColor(Color.WHITE, 0.95f),
                alphaColor(Color.WHITE, 0.66f),
                alphaColor(Color.WHITE, 0.28f),
                Color.TRANSPARENT,
            )
        } else {
            intArrayOf(
                Color.TRANSPARENT,
                alphaColor(Color.WHITE, 0.28f),
                alphaColor(Color.WHITE, 0.66f),
                alphaColor(Color.WHITE, 0.95f),
            )
        }
        val fadeShader = LinearGradient(
            if (left) 0f else width - spread,
            0f,
            if (left) spread else width.toFloat(),
            0f,
            fadeColors,
            AURORA_FADE_STOPS,
            Shader.TileMode.CLAMP,
        )
        auroraPaint.shader = ComposeShader(colorShader, fadeShader, PorterDuff.Mode.SRC_IN)
        if (left) {
            canvas.drawRect(0f, 0f, spread, height.toFloat(), auroraPaint)
        } else {
            canvas.drawRect(width - spread, 0f, width.toFloat(), height.toFloat(), auroraPaint)
        }
    }

    private fun drawAuroraHorizontalBand(canvas: Canvas, top: Boolean, spread: Float) {
        val colorShader = auroraLongitudinalShader(true, width.toFloat(), effectProgress)
        val fadeColors = if (top) {
            intArrayOf(
                alphaColor(Color.WHITE, 0.95f),
                alphaColor(Color.WHITE, 0.66f),
                alphaColor(Color.WHITE, 0.28f),
                Color.TRANSPARENT,
            )
        } else {
            intArrayOf(
                Color.TRANSPARENT,
                alphaColor(Color.WHITE, 0.28f),
                alphaColor(Color.WHITE, 0.66f),
                alphaColor(Color.WHITE, 0.95f),
            )
        }
        val fadeShader = LinearGradient(
            0f,
            if (top) 0f else height - spread,
            0f,
            if (top) spread else height.toFloat(),
            fadeColors,
            AURORA_FADE_STOPS,
            Shader.TileMode.CLAMP,
        )
        auroraPaint.shader = ComposeShader(colorShader, fadeShader, PorterDuff.Mode.SRC_IN)
        if (top) {
            canvas.drawRect(0f, 0f, width.toFloat(), spread, auroraPaint)
        } else {
            canvas.drawRect(0f, height - spread, width.toFloat(), height.toFloat(), auroraPaint)
        }
    }

    private fun drawAuroraCore(canvas: Canvas, baseStroke: Float) {
        val core = Paint(edgePaint).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = max(1f, baseStroke * 0.55f)
            alpha = 235
            shader = auroraLongitudinalShader(false, height.toFloat(), effectProgress)
        }
        val half = core.strokeWidth / 2f
        if (positionSides) {
            canvas.drawLine(half, 0f, half, height.toFloat(), core)
            canvas.drawLine(width - half, 0f, width - half, height.toFloat(), core)
        }
        if (positionTop) {
            core.shader = auroraLongitudinalShader(true, width.toFloat(), effectProgress)
            canvas.drawLine(0f, half, width.toFloat(), half, core)
        }
        if (positionBottom) {
            core.shader = auroraLongitudinalShader(
                true, width.toFloat(), (effectProgress + 0.35f) % 1f
            )
            canvas.drawLine(0f, height - half, width.toFloat(), height - half, core)
        }
    }

    private fun auroraLongitudinalShader(
        horizontal: Boolean,
        length: Float,
        phase: Float,
    ): Shader {
        val safeLength = length.coerceAtLeast(1f)
        val colors = if (auroraColorMode == "multicolor") {
            AURORA_MULTI
        } else {
            intArrayOf(
                alphaColor(auroraBaseColor, 0.45f),
                alphaColor(auroraBaseColor, 0.95f),
                alphaColor(auroraBaseColor, 0.62f),
                alphaColor(auroraBaseColor, 1f),
                alphaColor(auroraBaseColor, 0.45f),
            )
        }
        val positions =
            if (auroraColorMode == "multicolor") AURORA_MULTI_STOPS else AURORA_SINGLE_STOPS
        val shader = if (horizontal) {
            LinearGradient(
                -safeLength, 0f, safeLength, 0f,
                colors, positions, Shader.TileMode.MIRROR
            )
        } else {
            LinearGradient(
                0f, -safeLength, 0f, safeLength,
                colors, positions, Shader.TileMode.MIRROR
            )
        }
        val matrix = Matrix()
        val offset = safeLength * 2f * phase
        if (horizontal) matrix.setTranslate(offset, 0f) else matrix.setTranslate(0f, offset)
        shader.setLocalMatrix(matrix)
        return shader
    }

    private fun alphaColor(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { applyRainbowGradient() }
            return
        }
        applyRainbowGradient()
    }

    private fun applyRainbowGradient() {
        val w = width.toFloat()
        val h = height.toFloat()
        val shader: Shader = if (isFrameStyle(edgeStyle)) {
            val matrix = Matrix().apply { postRotate(rainbowRotation, w / 2f, h / 2f) }
            SweepGradient(w / 2f, h / 2f, RAINBOW, null).apply {
                setLocalMatrix(matrix)
            }
        } else {
            val offset = (rainbowRotation / 360f) * h
            LinearGradient(
                0f, -offset, 0f, h - offset,
                RAINBOW, null,
                Shader.TileMode.REPEAT,
            )
        }
        edgePaint.shader = shader
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

        val animator = ValueAnimator.ofFloat(0f, totalSegments.toFloat()).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                val idx = v.toInt().coerceIn(0, totalSegments - 1)
                val local = v - idx
                if (active[idx]) {
                    alpha = when {
                        local < fadeFraction -> local / fadeFraction
                        local > 1f - fadeFraction -> (1f - local) / fadeFraction
                        else -> 1f
                    }
                } else {
                    alpha = 0f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    pulseAnimator = null
                }
            })
            start()
        }
        pulseAnimator = animator
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun startEffectAnimation() {
        if (animationEffect == "none") return
        if (effectAnimator?.isRunning == true) return

        val durationMs: Long = when (animationEffect) {
            "chase" -> 2500L
            "breathing" -> 3000L
            "sparkle" -> 100L
            "aurora" -> 6500L
            "comet", "wave" -> 2000L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                effectProgress = va.animatedValue as Float
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
        if (!useRainbowGradient) return
        if (edgePaint.shader == null) return
        if (animationEffect in movingEffect) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                rainbowRotation = va.animatedValue as Float
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
        private val WATCHED_KEYS = arrayOf(
            "edge_light_color_mode",
            "edge_light_custom_color",
            "edge_light_pulse_count",
            "edge_light_stroke_width",
            "edge_light_style",
            "edge_light_animation_effect",
            "edge_light_position_top",
            "edge_light_position_sides",
            "edge_light_position_bottom",
            "edge_light_aurora_color_mode",
        )

        private val movingEffect = arrayOf("wave", "sparkle", "chase", "comet", "aurora")
        private val AURORA_FADE_STOPS = floatArrayOf(0f, 0.22f, 0.58f, 1f)
        private val AURORA_SINGLE_STOPS = floatArrayOf(0f, 0.23f, 0.5f, 0.77f, 1f)
        private val AURORA_MULTI_STOPS =
            floatArrayOf(0f, 0.12f, 0.26f, 0.42f, 0.58f, 0.74f, 0.88f, 1f)
        private const val EDGE_LEFT = 0
        private const val EDGE_RIGHT = 1
        private const val EDGE_TOP = 2
        private const val EDGE_BOTTOM = 3
        private val AURORA_MULTI = intArrayOf(
            0xFF00E5FF.toInt(),
            0xFF00FFB3.toInt(),
            0xFF4D7CFF.toInt(),
            0xFF7C4DFF.toInt(),
            0xFFFF4FD8.toInt(),
            0xFF9C6BFF.toInt(),
            0xFF00D9FF.toInt(),
            0xFF00E5FF.toInt(),
        )

        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(), // red
            0xFFFF7F00.toInt(), // orange
            0xFFFFFF00.toInt(), // yellow
            0xFF00FF00.toInt(), // green
            0xFF0000FF.toInt(), // blue
            0xFF4B0082.toInt(), // indigo
            0xFF9400D3.toInt(), // violet
            0xFFFF0000.toInt(), // red
        )

        private fun isFrameStyle(style: String): Boolean {
            val s = style.trim()
            return s.equals("rounded", ignoreCase = true) ||
                s.equals("frame", ignoreCase = true)
        }
    }
}

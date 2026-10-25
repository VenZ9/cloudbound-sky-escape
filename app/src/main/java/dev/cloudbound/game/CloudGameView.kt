package dev.cloudbound.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom hardware-accelerated Android Canvas game view.
 * Renders parallax layered skies, floating islands with foliage,
 * custom hot-air balloon skins, animated hazards, and dynamic particle effects.
 */
class CloudGameView(
    context: Context,
    val engine: GameEngine,
    val audioEngine: AudioEngine? = null,
    val hapticEngine: HapticEngine? = null
) : View(context), SensorEventListener {

    var onSnapshot: ((GameSnapshot) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private val pathSecondary = Path()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastFrame = 0L
    private var snapshotTimer = 0f
    private var ambientTime = 0f
    private var tiltRegistered = false

    private var isDragging = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var initialBalloonX = 0.5f
    private var initialBalloonY = 0.6f

    private val density = resources.displayMetrics.density

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = true

        // Wire engine event hooks to audio and haptics
        engine.onCoinCollected = {
            audioEngine?.playCoin()
            hapticEngine?.vibrateCoin()
        }
        engine.onHazardHit = {
            audioEngine?.playHit()
            hapticEngine?.vibrateHazard()
        }
        engine.onGameOver = {
            audioEngine?.playGameOver()
            hapticEngine?.vibrateGameOver()
        }
    }

    fun setTiltEnabled(enabled: Boolean) {
        engine.tiltEnabled = enabled && accelerometer != null
        if (isAttachedToWindow) updateSensorRegistration()
    }

    fun pauseForLifecycle() {
        engine.pause()
        updateSensorRegistration()
        invalidate()
    }

    private fun updateSensorRegistration() {
        val shouldListen = isAttachedToWindow &&
                engine.state == RunState.PLAYING &&
                engine.tiltEnabled &&
                accelerometer != null &&
                sensorManager != null

        if (shouldListen && !tiltRegistered) {
            tiltRegistered = sensorManager!!.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        } else if (!shouldListen && tiltRegistered) {
            sensorManager?.unregisterListener(this)
            tiltRegistered = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensorRegistration()
    }

    override fun onDetachedFromWindow() {
        if (tiltRegistered) sensorManager?.unregisterListener(this)
        tiltRegistered = false
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Landscape axis is avoided by locking activity portrait.
        engine.tiltInput = (-event.values[0] / 4.5f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now
        ambientTime += dt

        engine.setViewport(width, height)
        engine.update(dt)

        drawParallaxSky(canvas)

        if (engine.state == RunState.PLAYING || engine.state == RunState.PAUSED || engine.state == RunState.GAME_OVER) {
            drawWorld(canvas)
        } else {
            drawAmbientMenu(canvas)
        }

        // Broadcast state snapshots to Compose UI
        snapshotTimer += dt
        if (snapshotTimer >= 0.08f) {
            snapshotTimer = 0f
            onSnapshot?.invoke(engine.snapshot())
            updateSensorRegistration()
        }

        postInvalidateDelayed(16L)
    }

    private fun drawParallaxSky(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Altitude progression changes sky tint
        val altFactor = min(1f, engine.worldDistance / 24f)
        val topColor = interpolateColor(0xff8bcfe0.toInt(), 0xff3b7596.toInt(), altFactor)
        val botColor = interpolateColor(0xffd8f4ea.toInt(), 0xff9dd3db.toInt(), altFactor)

        paint.shader = LinearGradient(0f, 0f, 0f, h, topColor, botColor, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Ambient Sun / Stratosphere glow
        val sunX = w * 0.82f
        val sunY = h * 0.16f
        paint.color = 0x2affffff
        c.drawCircle(sunX, sunY, w * 0.35f, paint)
        paint.color = 0x33fff7db
        c.drawCircle(sunX, sunY, w * 0.20f, paint)
        paint.color = 0x77ffffff
        c.drawCircle(sunX, sunY, w * 0.09f, paint)

        // Distant floating island silhouettes
        val driftSlow = (ambientTime * 0.06f) % 1.2f
        drawDistantIsland(c, (w * 0.15f - driftSlow * w * 0.12f), h * 0.32f, w * 0.28f)
        drawDistantIsland(c, (w * 0.88f - driftSlow * w * 0.10f), h * 0.65f, w * 0.35f)

        // Midground drifting clouds
        val cloudDrift1 = (ambientTime * 14f) % (w + 240f) - 120f
        val cloudDrift2 = ((ambientTime * 9f) + w * 0.5f) % (w + 260f) - 130f
        drawFluffyCloud(c, cloudDrift1, h * 0.22f + driftY(0.012f), w * 0.44f, 0x66ffffff)
        drawFluffyCloud(c, cloudDrift2, h * 0.48f - driftY(0.015f), w * 0.54f, 0x55ffffff)
        drawFluffyCloud(c, w * 0.45f + driftX(0.018f), h * 0.82f, w * 0.58f, 0x44ffffff)

        // High altitude wind streaks
        if (engine.worldDistance > 4f) {
            paint.color = 0x1affffff
            paint.strokeWidth = 2f * density
            paint.style = Paint.Style.STROKE
            val streakY1 = ((ambientTime * 180f) % (h * 1.2f)) - h * 0.1f
            val streakY2 = (((ambientTime + 1.2f) * 220f) % (h * 1.2f)) - h * 0.1f
            c.drawLine(w * 0.22f, streakY1, w * 0.22f, streakY1 + 45f * density, paint)
            c.drawLine(w * 0.74f, streakY2, w * 0.74f, streakY2 + 65f * density, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun driftY(rate: Float) = (sin(ambientTime * 0.26f) * height * rate).toFloat()
    private fun driftX(rate: Float) = (cos(ambientTime * 0.21f) * width * rate).toFloat()

    private fun drawAmbientMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val skin = engine.getSkin()

        // Gentle floating menu island with balloon moored above
        drawFloatingIsland(c, w * 0.5f, h * 0.78f, w * 0.62f, 1.1f)
        val bobbingY = h * 0.52f + sin(ambientTime * 1.6f) * (8f * density)
        drawHotAirBalloon(c, w * 0.5f, bobbingY, min(w, h) * 0.28f, sin(ambientTime * 0.9f) * 0.06f, skin, glow = true)
    }

    private fun drawWorld(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val skin = engine.getSkin()

        // Parallax foreground floating islands
        val scroll = (engine.worldDistance * h) % (h * 1.1f)
        drawFloatingIsland(c, w * 0.18f, h * 0.28f + scroll, w * 0.36f, 0.9f)
        drawFloatingIsland(c, w * 0.86f, h * 0.82f + scroll - h * 0.6f, w * 0.40f, 1.2f)
        drawFloatingIsland(c, w * 0.48f, -h * 0.35f + scroll, w * 0.32f, 1.0f)

        // Draw Hazards
        engine.hazards.forEach { hazard -> drawHazard(c, hazard) }

        // Draw Collectible Coins
        engine.collectibles.forEach { coin -> if (!coin.collected) drawCoin(c, coin) }

        // Draw Dynamic Particles
        engine.particles.forEach { puff ->
            paint.color = puff.color
            val alphaPct = (puff.life / puff.maxLife).coerceIn(0f, 1f)
            paint.alpha = (alphaPct * 220).toInt().coerceIn(0, 240)
            val radius = (1.1f - alphaPct * 0.5f) * 8f * density * puff.sizeScale
            c.drawCircle(puff.x * w, puff.y * h, radius, paint)
            paint.alpha = 255
        }

        // Draw Balloon with tilt and invulnerability flicker
        val isInvul = engine.invulnerability > 0f
        val shouldDrawBalloon = !isInvul || ((engine.invulnerability * 14).toInt() % 2 == 0)
        if (shouldDrawBalloon) {
            val steerTilt = (engine.targetX - engine.balloonX).coerceIn(-0.35f, 0.35f) * 1.6f
            val finalTilt = if (engine.tiltEnabled) (engine.tiltInput * 0.35f) else steerTilt
            drawHotAirBalloon(
                c,
                engine.balloonX * w,
                engine.balloonY * h,
                min(w, h) * 0.23f,
                finalTilt,
                skin,
                glow = isInvul
            )
        }

        // Subdued vignette on pause / game over
        if (engine.state == RunState.PAUSED || engine.state == RunState.GAME_OVER) {
            paint.color = 0x440e2b38
            c.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun drawFluffyCloud(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        paint.color = color
        val r = size * 0.16f
        c.drawCircle(cx - r * 1.4f, cy + r * 0.15f, r * 0.85f, paint)
        c.drawCircle(cx - r * 0.7f, cy - r * 0.35f, r * 1.15f, paint)
        c.drawCircle(cx + r * 0.2f, cy - r * 0.55f, r * 1.35f, paint)
        c.drawCircle(cx + r * 1.1f, cy - r * 0.25f, r * 1.05f, paint)
        c.drawCircle(cx + r * 1.7f, cy + r * 0.18f, r * 0.78f, paint)
        c.drawRoundRect(cx - r * 1.6f, cy - r * 0.1f, cx + r * 1.9f, cy + r * 0.8f, r * 0.6f, r * 0.6f, paint)
    }

    private fun drawDistantIsland(c: Canvas, x: Float, y: Float, size: Float) {
        paint.color = 0x556e8f85
        c.drawOval(x - size * 0.45f, y - size * 0.08f, x + size * 0.45f, y + size * 0.10f, paint)
        path.reset()
        path.moveTo(x - size * 0.38f, y)
        path.lineTo(x, y + size * 0.40f)
        path.lineTo(x + size * 0.35f, y)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawFloatingIsland(c: Canvas, x: Float, y: Float, size: Float, scale: Float) {
        if (y < -size || y > height + size) return

        // Earthen crag underside with multi-facet shading
        path.reset()
        path.moveTo(x - size * 0.42f, y)
        path.cubicTo(
            x - size * 0.30f, y + size * 0.32f,
            x - size * 0.15f, y + size * 0.46f,
            x, y + size * 0.64f
        )
        path.cubicTo(
            x + size * 0.18f, y + size * 0.40f,
            x + size * 0.32f, y + size * 0.30f,
            x + size * 0.42f, y
        )
        path.close()
        paint.color = 0xff8d6b4f.toInt() // Warm rock earth
        c.drawPath(path, paint)

        // Shaded side facet
        pathSecondary.reset()
        pathSecondary.moveTo(x, y + size * 0.64f)
        pathSecondary.cubicTo(
            x + size * 0.18f, y + size * 0.40f,
            x + size * 0.32f, y + size * 0.30f,
            x + size * 0.42f, y
        )
        pathSecondary.lineTo(x, y + size * 0.05f)
        pathSecondary.close()
        paint.color = 0xff73553d.toInt()
        c.drawPath(pathSecondary, paint)

        // Lush grass plateau
        paint.color = 0xff4e8e6b.toInt() // Deep emerald fringe
        c.drawOval(x - size * 0.46f, y - size * 0.12f, x + size * 0.46f, y + size * 0.10f, paint)
        paint.color = 0xff86b864.toInt() // Sunlit lime grass top
        c.drawOval(x - size * 0.42f, y - size * 0.18f, x + size * 0.42f, y + size * 0.03f, paint)

        // Hanging foliage vines
        paint.color = 0xff3b6b50.toInt()
        c.drawRoundRect(x - size * 0.22f, y + size * 0.02f, x - size * 0.18f, y + size * 0.26f, 3f * density, 3f * density, paint)
        c.drawRoundRect(x + size * 0.12f, y + size * 0.04f, x + size * 0.16f, y + size * 0.20f, 3f * density, 3f * density, paint)

        // Stylized trees
        paint.color = 0xff5a4533.toInt()
        c.drawRect(x - size * 0.16f, y - size * 0.24f, x - size * 0.14f, y - size * 0.06f, paint)
        c.drawRect(x + size * 0.18f, y - size * 0.22f, x + size * 0.20f, y - size * 0.05f, paint)
        paint.color = 0xff3e7a5c.toInt()
        c.drawCircle(x - size * 0.15f, y - size * 0.26f, size * 0.065f, paint)
        c.drawCircle(x + size * 0.19f, y - size * 0.24f, size * 0.055f, paint)
    }

    private fun drawHotAirBalloon(
        c: Canvas,
        x: Float,
        y: Float,
        size: Float,
        tilt: Float,
        skin: BalloonSkin,
        glow: Boolean
    ) {
        c.save()
        c.rotate(tilt * 22f, x, y)

        val envW = size * 0.64f
        val envH = size * 0.72f
        val envTop = y - size * 0.44f
        val envBottom = envTop + envH

        // Invulnerability / divine aura
        if (glow) {
            paint.color = 0x44ffffff
            c.drawCircle(x, y - size * 0.08f, size * 0.52f, paint)
            paint.color = 0x22fcd34d
            c.drawCircle(x, y - size * 0.08f, size * 0.62f, paint)
        }

        // Rigging ropes
        strokePaint.color = 0xff54382c.toInt()
        strokePaint.strokeWidth = 1.6f * density
        c.drawLine(x - envW * 0.32f, envBottom - envH * 0.08f, x - size * 0.09f, y + size * 0.26f, strokePaint)
        c.drawLine(x + envW * 0.32f, envBottom - envH * 0.08f, x + size * 0.09f, y + size * 0.26f, strokePaint)
        c.drawLine(x - envW * 0.15f, envBottom - envH * 0.04f, x - size * 0.05f, y + size * 0.26f, strokePaint)
        c.drawLine(x + envW * 0.15f, envBottom - envH * 0.04f, x + size * 0.05f, y + size * 0.26f, strokePaint)

        // Burner frame & flickering flame
        paint.color = 0xff6b7280.toInt()
        c.drawRect(x - size * 0.06f, y + size * 0.18f, x + size * 0.06f, y + size * 0.21f, paint)

        // Animated burner flame
        val flameFlicker = sin(ambientTime * 22f) * 0.15f + 1f
        paint.color = 0xddf59e0b.toInt()
        c.drawCircle(x, y + size * 0.16f, size * 0.045f * flameFlicker, paint)
        paint.color = 0xfffef08a.toInt()
        c.drawCircle(x, y + size * 0.15f, size * 0.025f * flameFlicker, paint)

        // Wicker / crafted basket
        val basketLeft = x - size * 0.11f
        val basketRight = x + size * 0.11f
        val basketTop = y + size * 0.24f
        val basketBottom = y + size * 0.37f
        val basketRadius = 4f * density

        paint.color = skin.basketColor
        c.drawRoundRect(basketLeft, basketTop, basketRight, basketBottom, basketRadius, basketRadius, paint)

        // Basket rim and woven accents
        paint.color = 0x44ffffff
        c.drawRoundRect(basketLeft, basketTop, basketRight, basketTop + 3.5f * density, basketRadius, basketRadius, paint)
        paint.color = 0x33000000
        c.drawRect(basketLeft + 2f, basketTop + 6f * density, basketRight - 2f, basketTop + 8f * density, paint)

        // Primary Balloon Envelope
        val envelopeRect = RectF(x - envW / 2, envTop, x + envW / 2, envBottom)
        paint.color = skin.primaryColor
        c.drawOval(envelopeRect, paint)

        // Pattern variations based on equipped skin
        when (skin.pattern) {
            SkinPattern.STRIPES -> {
                // Secondary curved side stripes
                paint.color = skin.secondaryColor
                path.reset()
                path.moveTo(x - envW * 0.44f, y - envH * 0.08f)
                path.quadTo(x, envTop + envH * 0.28f, x + envW * 0.44f, y - envH * 0.08f)
                path.lineTo(x + envW * 0.38f, y + envH * 0.18f)
                path.quadTo(x, envBottom, x - envW * 0.38f, y + envH * 0.18f)
                path.close()
                c.drawPath(path, paint)

                // Accent center stripe
                paint.color = skin.accentColor
                c.drawOval(x - envW * 0.16f, envTop + 4f, x + envW * 0.16f, envBottom - 8f, paint)
            }
            SkinPattern.AZURE -> {
                // Sleek aerodynamic chevron stripes
                paint.color = skin.secondaryColor
                path.reset()
                path.moveTo(x, envTop + 6f)
                path.lineTo(x + envW * 0.45f, y)
                path.lineTo(x + envW * 0.35f, y + envH * 0.24f)
                path.lineTo(x, envBottom - 8f)
                path.lineTo(x - envW * 0.35f, y + envH * 0.24f)
                path.lineTo(x - envW * 0.45f, y)
                path.close()
                c.drawPath(path, paint)

                // White aero star
                paint.color = skin.accentColor
                c.drawCircle(x, y - envH * 0.08f, envW * 0.14f, paint)
            }
            SkinPattern.SUNBURST -> {
                // Radiant golden sun rays
                paint.color = skin.secondaryColor
                c.drawCircle(x, y - envH * 0.06f, envW * 0.32f, paint)
                paint.color = skin.accentColor
                c.drawCircle(x, y - envH * 0.06f, envW * 0.20f, paint)
                paint.color = 0xffffffff.toInt()
                c.drawCircle(x, y - envH * 0.06f, envW * 0.10f, paint)
            }
            SkinPattern.STORM -> {
                // Electric thunderbolt pattern across envelope
                paint.color = skin.secondaryColor
                c.drawOval(x - envW * 0.38f, envTop + 10f, x + envW * 0.38f, envBottom - 12f, paint)
                paint.color = skin.accentColor
                path.reset()
                path.moveTo(x + 4f, envTop + envH * 0.12f)
                path.lineTo(x - envW * 0.22f, y - 4f)
                path.lineTo(x + 2f, y - 6f)
                path.lineTo(x - 8f, envBottom - envH * 0.16f)
                path.lineTo(x + envW * 0.24f, y + 2f)
                path.lineTo(x + 6f, y + 4f)
                path.close()
                c.drawPath(path, paint)
            }
            SkinPattern.AURORA -> {
                // Iridescent waves
                paint.color = skin.secondaryColor
                path.reset()
                path.moveTo(x - envW * 0.46f, y - envH * 0.12f)
                path.cubicTo(x - envW * 0.2f, y - envH * 0.35f, x + envW * 0.2f, y + envH * 0.15f, x + envW * 0.46f, y - envH * 0.12f)
                path.lineTo(x + envW * 0.40f, y + envH * 0.20f)
                path.cubicTo(x + envW * 0.2f, y + envH * 0.40f, x - envW * 0.2f, y, x - envW * 0.40f, y + envH * 0.20f)
                path.close()
                c.drawPath(path, paint)

                paint.color = skin.accentColor
                c.drawCircle(x, y - envH * 0.08f, envW * 0.12f, paint)
            }
        }

        // Specular 3D envelope gloss highlight
        paint.color = 0x55ffffff
        c.drawOval(
            x - envW * 0.38f,
            envTop + envH * 0.12f,
            x - envW * 0.15f,
            envTop + envH * 0.48f,
            paint
        )

        c.restore()
    }

    private fun drawCoin(c: Canvas, coin: Coin) {
        val x = coin.x * width
        val y = coin.y * height

        // 3D spinning squish animation
        val spin = cos(ambientTime * 5.5f + coin.phase)
        val squish = abs(spin).coerceAtLeast(0.18f)
        val r = 12f * density

        // Sparkling outer glow aura
        paint.color = 0x33f59e0b
        c.drawCircle(x, y, (r + 7f * density) * (1f + sin(ambientTime * 6f + coin.phase) * 0.08f), paint)

        // Outer coin disk
        paint.color = 0xfff59e0b.toInt() // Burnished gold edge
        c.drawOval(x - r * squish, y - r, x + r * squish, y + r, paint)

        // Inner coin core
        paint.color = 0xfffcd34d.toInt() // Brilliant gold center
        c.drawOval(x - (r - 2.5f * density) * squish, y - (r - 2.5f * density), x + (r - 2.5f * density) * squish, y + (r - 2.5f * density), paint)

        // Embossed altitude diamond
        if (squish > 0.45f) {
            paint.color = 0xffffffff.toInt()
            path.reset()
            path.moveTo(x, y - (r * 0.45f))
            path.lineTo(x + (r * 0.40f * squish), y)
            path.lineTo(x, y + (r * 0.45f))
            path.lineTo(x - (r * 0.40f * squish), y)
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun drawHazard(c: Canvas, h: Hazard) {
        val x = h.x * width
        val y = h.y * height
        val s = min(width, height) * h.radius

        when (h.type) {
            HazardType.BIRD -> {
                // Aerodynamic migratory bird with flapping wings
                paint.color = 0xff334155.toInt()
                val flap = sin(ambientTime * 12f + h.seed) * s * 0.35f
                strokePaint.color = 0xff334155.toInt()
                strokePaint.strokeWidth = 3.6f * density
                strokePaint.strokeCap = Paint.Cap.ROUND

                path.reset()
                path.moveTo(x - s, y + flap)
                path.quadTo(x - s * 0.4f, y - s * 0.45f - flap, x, y)
                path.quadTo(x + s * 0.4f, y - s * 0.45f - flap, x + s, y + flap)
                c.drawPath(path, strokePaint)

                // Beak & bird head
                paint.color = 0xff1e293b.toInt()
                c.drawCircle(x, y - 2f * density, 3.5f * density, paint)
            }
            HazardType.STORM -> {
                // Ominous stormy thundercloud
                paint.color = 0xff475569.toInt() // Dark slate
                c.drawCircle(x - s * 0.48f, y + s * 0.05f, s * 0.58f, paint)
                c.drawCircle(x, y - s * 0.22f, s * 0.72f, paint)
                c.drawCircle(x + s * 0.52f, y + s * 0.05f, s * 0.54f, paint)

                // Internal electrical lightning glow
                val internalFlash = (sin(ambientTime * 14f + h.seed) > 0.7f)
                if (internalFlash) {
                    paint.color = 0x66fef08a
                    c.drawCircle(x, y - s * 0.15f, s * 0.45f, paint)
                }

                // Discharging bolt
                paint.color = 0xfffcd34d.toInt()
                path.reset()
                path.moveTo(x + s * 0.04f, y + s * 0.18f)
                path.lineTo(x - s * 0.16f, y + s * 0.52f)
                path.lineTo(x + s * 0.02f, y + s * 0.46f)
                path.lineTo(x - s * 0.06f, y + s * 0.85f)
                path.lineTo(x + s * 0.28f, y + s * 0.34f)
                path.lineTo(x + s * 0.08f, y + s * 0.40f)
                path.close()
                c.drawPath(path, paint)
            }
            HazardType.ROCK, HazardType.PASSAGE -> {
                // Jagged floating crag with multi-angle rock facets
                path.reset()
                path.moveTo(x - s, y + s * 0.22f)
                path.lineTo(x - s * 0.75f, y - s * 0.60f)
                path.lineTo(x - s * 0.05f, y - s * 1.05f)
                path.lineTo(x + s * 0.72f, y - s * 0.52f)
                path.lineTo(x + s * 1.05f, y + s * 0.45f)
                path.lineTo(x + s * 0.25f, y + s * 0.80f)
                path.lineTo(x - s * 0.62f, y + s * 0.64f)
                path.close()
                paint.color = 0xff64748b.toInt() // Slate crag
                c.drawPath(path, paint)

                // Highlighted rock facet
                pathSecondary.reset()
                pathSecondary.moveTo(x - s * 0.05f, y - s * 1.05f)
                pathSecondary.lineTo(x + s * 0.72f, y - s * 0.52f)
                pathSecondary.lineTo(x, y)
                pathSecondary.close()
                paint.color = 0xff94a3b8.toInt()
                c.drawPath(pathSecondary, paint)
            }
            HazardType.LIGHTNING -> {
                val isDischarging = h.telegraphed
                if (!isDischarging) {
                    // Telegraph warning shimmer beam
                    paint.color = 0x22c084fc
                    c.drawRect(x - s * 0.35f, y - s * 2f, x + s * 0.35f, y + s * 2f, paint)
                    strokePaint.color = 0x77c084fc
                    strokePaint.strokeWidth = 2f * density
                    c.drawLine(x, y - s * 2f, x, y + s * 2f, strokePaint)
                } else {
                    // Full electric strike
                    paint.color = 0x33e879f9
                    c.drawRoundRect(x - s * 0.6f, y - s * 1.8f, x + s * 0.6f, y + s * 1.8f, s * 0.3f, s * 0.3f, paint)

                    paint.color = 0xffd946ef.toInt()
                    path.reset()
                    path.moveTo(x + s * 0.16f, y - s * 1.6f)
                    path.lineTo(x - s * 0.30f, y - s * 0.2f)
                    path.lineTo(x, y - s * 0.18f)
                    path.lineTo(x - s * 0.22f, y + s * 1.6f)
                    path.lineTo(x + s * 0.35f, y + s * 0.15f)
                    path.lineTo(x + s * 0.05f, y + s * 0.18f)
                    path.close()
                    c.drawPath(path, paint)

                    paint.color = 0xffffffff.toInt()
                    c.drawCircle(x, y, s * 0.18f, paint)
                }
            }
            HazardType.GUST -> {
                // Translucent wind current
                strokePaint.color = 0x55ffffff
                strokePaint.strokeWidth = 3f * density
                strokePaint.strokeCap = Paint.Cap.ROUND
                path.reset()
                path.moveTo(x - s * 1.5f, y)
                path.cubicTo(x - s * 0.5f, y - s * 0.5f, x + s * 0.5f, y + s * 0.5f, x + s * 1.5f, y)
                c.drawPath(path, strokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                touchDownX = event.x
                touchDownY = event.y
                initialBalloonX = engine.balloonX
                initialBalloonY = engine.balloonY

                if (!engine.tiltEnabled) {
                    engine.setTarget(event.x / w, event.y / h)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !engine.tiltEnabled) {
                    val deltaX = (event.x - touchDownX) / w
                    val deltaY = (event.y - touchDownY) / h
                    engine.setTarget(initialBalloonX + deltaX, initialBalloonY + deltaY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun interpolateColor(colorA: Int, colorB: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (colorA ushr 24 and 0xff) + (((colorB ushr 24 and 0xff) - (colorA ushr 24 and 0xff)) * f).toInt()
        val r = (colorA ushr 16 and 0xff) + (((colorB ushr 16 and 0xff) - (colorA ushr 16 and 0xff)) * f).toInt()
        val g = (colorA ushr 8 and 0xff) + (((colorB ushr 8 and 0xff) - (colorA ushr 8 and 0xff)) * f).toInt()
        val b = (colorA and 0xff) + (((colorB and 0xff) - (colorA and 0xff)) * f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

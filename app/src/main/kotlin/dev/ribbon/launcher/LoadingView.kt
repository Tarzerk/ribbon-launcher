package dev.ribbon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Native "channel is starting" screen for TV-mode tiles. Covers the web page (so the site never
 * flashes or jumps around) until the video is actually playing, then fades away.
 */
class LoadingView(
    ctx: Context,
    private val title: String,
    private val sub: String?,
    private val colors: IntArray,
    private val badge: Glyph,
) : View(ctx) {

    private val dp = resources.displayMetrics.density
    private var shownAt = SystemClock.uptimeMillis()
    private val rect = RectF()

    private val bgPaint = Paint()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.LEFT; letterSpacing = 0.18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B4E.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x22FFFFFF
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE
    }

    /** Shows it again from the start (used when the page reloads). */
    fun restart() {
        animate().cancel()
        alpha = 1f
        shownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun dismiss(onGone: () -> Unit) {
        animate().alpha(0f).setDuration(420).withEndAction(onGone).start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val tint = Color.argb(150, Color.red(colors[1]), Color.green(colors[1]), Color.blue(colors[1]))
        bgPaint.shader = RadialGradient(w / 2f, h * 0.42f, maxOf(w, h) * 0.75f,
            intArrayOf(tint, 0xFF140810.toInt(), Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (SystemClock.uptimeMillis() - shownAt) / 1000f
        c.drawColor(Color.BLACK) // fully opaque: nothing from the page shows through
        c.drawRect(0f, 0f, w, h, bgPaint)

        // Gentle entrance: everything eases up and in over the first ~0.5 s.
        val enter = easeOut(min(1f, t / 0.5f))
        val lift = (1f - enter) * 14f * dp
        val a = (255 * enter).toInt()

        // Badge, styled like the Ribbon tile, with a soft breathing glow.
        val size = 128f * dp
        val cx = w / 2f
        val cy = h * 0.40f + lift
        val radius = size * 0.28f
        val breathe = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 2.4f)
        glowPaint.color = colors[0]
        for (i in 4 downTo 1) {
            val spread = i * 7f * dp
            glowPaint.alpha = ((14 + 10 * breathe) * enter / i).toInt()
            rect.set(cx - size / 2 - spread, cy - size / 2 - spread, cx + size / 2 + spread, cy + size / 2 + spread)
            c.drawRoundRect(rect, radius + spread, radius + spread, glowPaint)
        }
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        badgePaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, colors[0], colors[1], Shader.TileMode.CLAMP)
        badgePaint.alpha = a
        c.drawRoundRect(rect, radius, radius, badgePaint)
        val g = size * 0.56f
        c.save()
        c.translate(cx - g / 2, cy - g / 2)
        c.scale(g / 24f, g / 24f)
        GlyphPainter.draw(c, badge, Color.argb(a, 255, 255, 255))
        c.restore()

        // Channel name and a pulsing "live" line.
        titlePaint.textSize = 34f * dp; titlePaint.alpha = a
        val titleY = cy + size / 2 + 58f * dp
        c.drawText(title, cx, titleY, titlePaint)
        sub?.let {
            val label = it.uppercase()
            subPaint.textSize = 14f * dp; subPaint.alpha = (200 * enter).toInt()
            val dotR = 5f * dp
            val gap = 10f * dp
            val total = dotR * 2 + gap + subPaint.measureText(label)
            val left = cx - total / 2
            val y = titleY + 32f * dp
            dotPaint.alpha = ((0.45f + 0.55f * breathe) * 255 * enter).toInt()
            c.drawCircle(left + dotR, y - 5f * dp, dotR, dotPaint)
            c.drawText(label, left + dotR * 2 + gap, y, subPaint)
        }

        // Spinner: a smooth arc that grows and shrinks as it turns.
        val sr = 18f * dp
        val sy = h * 0.84f
        trackPaint.strokeWidth = 3f * dp; arcPaint.strokeWidth = 3f * dp
        trackPaint.alpha = (34 * enter).toInt(); arcPaint.alpha = (230 * enter).toInt()
        rect.set(cx - sr, sy - sr, cx + sr, sy + sr)
        c.drawOval(rect, trackPaint)
        val spin = (t * 300f) % 360f
        val sweep = 40f + 220f * (0.5f - 0.5f * cos(t * 2f * PI.toFloat() / 1.6f))
        c.drawArc(rect, spin - sweep / 2, sweep, false, arcPaint)

        // If it's taking a while, say so (quietly).
        if (t > 15f) {
            hintPaint.textSize = 14f * dp
            hintPaint.alpha = (150 * min(1f, (t - 15f) / 0.6f)).toInt()
            c.drawText("Still connecting… press ☰ for options", cx, sy + sr + 34f * dp, hintPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
}

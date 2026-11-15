package dev.ribbon.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** Icon glyphs drawn as white outlines on a 24x24 grid (stroke width 2), or as a bold letter. */
sealed class Glyph {
    object Gamepad : Glyph()
    object Cloud : Glyph()
    object Bolt : Glyph()
    object Bluetooth : Glyph()
    object Gear : Glyph()
    object Wifi : Glyph()
    object Monitor : Glyph()
    object Speaker : Glyph()
    object Bars : Glyph()
    object Cast : Glyph()
    object Play : Glyph()
    object Music : Glyph()
    object Trophy : Glyph()
    object Ball : Glyph()
    object Film : Glyph()
    object Shield : Glyph()
    object Home : Glyph()
    object Grid : Glyph()
    object Plug : Glyph()
    object Antenna : Glyph()
    object Globe : Glyph()
    data class Letter(val text: String) : Glyph()
}

object GlyphPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val paths = HashMap<Glyph, Path>()

    private fun cached(g: Glyph, build: Path.() -> Unit) = paths.getOrPut(g) { Path().apply(build) }

    /** Draws [g] centered in a 24x24 box at the canvas origin. */
    fun draw(c: Canvas, g: Glyph, color: Int) {
        stroke.color = color
        fill.color = color
        when (g) {
            Glyph.Gamepad -> {
                r.set(2f, 6.5f, 22f, 17.5f); c.drawRoundRect(r, 5f, 5f, stroke)
                c.drawLine(6f, 12f, 10f, 12f, stroke); c.drawLine(8f, 10f, 8f, 14f, stroke)
                c.drawCircle(15.5f, 11f, 1.1f, fill); c.drawCircle(17.8f, 13.2f, 1.1f, fill)
            }
            Glyph.Cloud -> c.drawPath(cached(g) {
                moveTo(6.5f, 18f)
                cubicTo(3.3f, 18f, 2.4f, 13.6f, 5.2f, 12.4f)
                cubicTo(5.2f, 8.2f, 10.2f, 6.6f, 12.6f, 9.1f)
                cubicTo(14.3f, 6.6f, 19.6f, 7.7f, 19.1f, 12f)
                cubicTo(22f, 12.7f, 21.4f, 18f, 17.8f, 18f)
                close()
            }, stroke)
            Glyph.Bolt -> c.drawPath(cached(g) {
                moveTo(13f, 3f); lineTo(5f, 14f); lineTo(11f, 14f); lineTo(10f, 21f); lineTo(18f, 10f); lineTo(12f, 10f); close()
            }, stroke)
            Glyph.Bluetooth -> c.drawPath(cached(g) {
                moveTo(7f, 8f); lineTo(17f, 16f); lineTo(12f, 20f); lineTo(12f, 4f); lineTo(17f, 8f); lineTo(7f, 16f)
            }, stroke)
            Glyph.Gear -> {
                c.drawCircle(12f, 12f, 6.5f, stroke); c.drawCircle(12f, 12f, 2.4f, stroke)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 2.6f, 12f, 4.6f, stroke); c.restore()
                }
            }
            Glyph.Wifi -> {
                for (rad in floatArrayOf(4.5f, 8.5f, 12.5f)) {
                    r.set(12f - rad, 19f - rad, 12f + rad, 19f + rad)
                    c.drawArc(r, 225f, 90f, false, stroke)
                }
                c.drawCircle(12f, 19f, 1.2f, fill)
            }
            Glyph.Monitor -> {
                r.set(3f, 4f, 21f, 16f); c.drawRoundRect(r, 2f, 2f, stroke)
                c.drawLine(8f, 20f, 16f, 20f, stroke); c.drawLine(12f, 16f, 12f, 20f, stroke)
            }
            Glyph.Speaker -> {
                c.drawPath(cached(g) { moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close() }, stroke)
                r.set(11f, 8f, 19f, 16f); c.drawArc(r, -50f, 100f, false, stroke)
                r.set(9f, 5f, 21f, 19f); c.drawArc(r, -50f, 100f, false, stroke)
            }
            Glyph.Bars -> {
                c.drawLine(5f, 19f, 5f, 15f, stroke); c.drawLine(9.67f, 19f, 9.67f, 12f, stroke)
                c.drawLine(14.33f, 19f, 14.33f, 8.5f, stroke); c.drawLine(19f, 19f, 19f, 5f, stroke)
            }
            Glyph.Cast -> {
                c.drawPath(cached(g) { moveTo(3f, 8f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 16f); quadTo(21f, 18f, 19f, 18f); lineTo(14f, 18f) }, stroke)
                r.set(-2f, 11f, 8f, 21f); c.drawArc(r, -90f, 90f, false, stroke)
                r.set(-6f, 7f, 12f, 25f); c.drawArc(r, -90f, 90f, false, stroke)
                c.drawCircle(3.5f, 19.5f, 1.2f, fill)
            }
            Glyph.Play -> c.drawPath(cached(g) { moveTo(8.5f, 5.5f); lineTo(19f, 12f); lineTo(8.5f, 18.5f); close() }, fill)
            Glyph.Music -> {
                c.drawCircle(7f, 17.5f, 2.6f, stroke); c.drawCircle(17f, 15.5f, 2.6f, stroke)
                c.drawPath(cached(g) { moveTo(9.6f, 17.5f); lineTo(9.6f, 5.5f); lineTo(19.6f, 3.5f); lineTo(19.6f, 15.5f) }, stroke)
                c.drawLine(9.6f, 9.5f, 19.6f, 7.5f, stroke)
            }
            Glyph.Trophy -> {
                c.drawPath(cached(g) { moveTo(7.5f, 4f); lineTo(16.5f, 4f); lineTo(16.5f, 9.5f); cubicTo(16.5f, 15f, 7.5f, 15f, 7.5f, 9.5f); close() }, stroke)
                r.set(3.5f, 5f, 8.5f, 10f); c.drawArc(r, 90f, 180f, false, stroke)
                r.set(15.5f, 5f, 20.5f, 10f); c.drawArc(r, -90f, 180f, false, stroke)
                c.drawLine(12f, 13.8f, 12f, 18f, stroke); c.drawLine(8f, 20f, 16f, 20f, stroke)
            }
            Glyph.Ball -> {
                c.save(); c.rotate(-40f, 12f, 12f)
                r.set(3f, 7f, 21f, 17f); c.drawOval(r, stroke)
                c.drawLine(9f, 12f, 15f, 12f, stroke)
                for (x in floatArrayOf(10.5f, 12f, 13.5f)) c.drawLine(x, 10.8f, x, 13.2f, stroke)
                c.restore()
            }
            Glyph.Film -> {
                r.set(4f, 4f, 20f, 20f); c.drawRoundRect(r, 2f, 2f, stroke)
                c.drawLine(8f, 4f, 8f, 20f, stroke); c.drawLine(16f, 4f, 16f, 20f, stroke); c.drawLine(4f, 12f, 20f, 12f, stroke)
            }
            Glyph.Shield -> c.drawPath(cached(g) {
                moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f)
                cubicTo(19f, 16f, 15.5f, 19.5f, 12f, 21f)
                cubicTo(8.5f, 19.5f, 5f, 16f, 5f, 11f)
                lineTo(5f, 6f); close()
            }, stroke)
            Glyph.Home -> c.drawPath(cached(g) {
                moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
                moveTo(6.5f, 9.5f); lineTo(6.5f, 20f); lineTo(17.5f, 20f); lineTo(17.5f, 9.5f)
            }, stroke)
            Glyph.Grid -> for (x in floatArrayOf(4f, 13f)) for (y in floatArrayOf(4f, 13f)) {
                r.set(x, y, x + 7f, y + 7f); c.drawRoundRect(r, 1.8f, 1.8f, stroke)
            }
            Glyph.Plug -> {
                c.drawPath(cached(g) { moveTo(7f, 9f); lineTo(17f, 9f); lineTo(17f, 12f); cubicTo(17f, 15f, 15f, 17f, 12f, 17f); cubicTo(9f, 17f, 7f, 15f, 7f, 12f); close() }, stroke)
                c.drawLine(9.5f, 4f, 9.5f, 9f, stroke); c.drawLine(14.5f, 4f, 14.5f, 9f, stroke); c.drawLine(12f, 17f, 12f, 21f, stroke)
            }
            Glyph.Antenna -> {
                c.drawLine(12f, 10f, 12f, 21f, stroke); c.drawLine(8f, 21f, 12f, 10f, stroke); c.drawLine(16f, 21f, 12f, 10f, stroke)
                c.drawCircle(12f, 8f, 1.4f, fill)
                r.set(7.5f, 3.5f, 16.5f, 12.5f); c.drawArc(r, 200f, 140f, false, stroke)
            }
            Glyph.Globe -> {
                c.drawCircle(12f, 12f, 8.5f, stroke)
                r.set(8f, 3.5f, 16f, 20.5f); c.drawOval(r, stroke)
                c.drawLine(3.5f, 12f, 20.5f, 12f, stroke)
            }
            is Glyph.Letter -> {
                text.color = color
                text.textSize = if (g.text.length > 1) 11f else 15f
                val fm = text.fontMetrics
                c.drawText(g.text, 12f, 12f - (fm.ascent + fm.descent) / 2f, text)
            }
        }
    }
}

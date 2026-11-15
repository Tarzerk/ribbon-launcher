package dev.ribbon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole home screen, drawn on one canvas: categories run left to right, the selected
 * category's items run top to bottom, and a ribbon of light drifts behind them.
 */
class RibbonView(ctx: Context) : View(ctx) {

    var onActivate: ((Item) -> Unit)? = null
    /** ☰ (menu) on the selected item, e.g. to remove a saved tile. */
    var onOptions: ((Item) -> Unit)? = null
    var onSound: ((Sfx) -> Unit)? = null

    private var cats: List<Category> = emptyList()
    private var status: String? = null
    private var cat = 0
    private var item = 0
    private val remembered = HashMap<String, String>() // category name -> selected item key

    // Animated state.
    private var catPos = 0f
    private var itemPos = 0f
    private var themeT = 1f // 1 = game colors, 0 = media colors
    private var listFade = 1f
    private var press = 0f
    private var time = 0f
    private var lastFrame = 0L

    private val dp = resources.displayMetrics.density

    private val bgPaint = Paint()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val waveGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wavePath = Path()
    private val iconPaints = HashMap<IntArray, Paint>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowSteps = listOf(18f to 0.10f, 11f to 0.16f, 5f to 0.28f)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val rect = RectF()

    private val clockFmt = SimpleDateFormat("h:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE M/d", Locale.getDefault())
    private var clockText = ""
    private var dateText = ""
    private var clockStamp = 0L

    private var stickX = 0
    private var stickY = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- Data ---------------------------------------------------------------------------

    fun setHome(home: Home) {
        val oldCat = cats.getOrNull(cat)?.name
        val oldItem = cats.getOrNull(cat)?.items?.getOrNull(item)?.key
        cats = home.categories
        status = home.status
        if (oldCat == null) {
            focusHome(animate = false)
        } else {
            cat = cats.indexOfFirst { it.name == oldCat }.takeIf { it >= 0 } ?: homeIndex()
            item = cats[cat].items.indexOfFirst { it.key == oldItem }.coerceAtLeast(0)
            itemPos = item.toFloat()
        }
        invalidate()
    }

    /** Inputs if the TV has any connected; otherwise the first tab after Settings (usually Video). */
    private fun homeIndex(): Int = cats.indexOfFirst { it.name == Catalog.HOME_CATEGORY }.takeIf { it >= 0 }
        ?: if (cats.size > 1) 1 else 0

    fun focusHome(animate: Boolean = true) {
        if (cats.isEmpty()) return
        cat = homeIndex()
        item = 0
        if (!animate) {
            catPos = cat.toFloat(); itemPos = 0f
            themeT = if (cats[cat].theme == Theme.GAME) 1f else 0f
        } else listFade = 0f
        invalidate()
    }

    // ---- Input --------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val first = event.repeatCount == 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> sound(moveCat(-1), Sfx.CATEGORY, first)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> sound(moveCat(1), Sfx.CATEGORY, first)
            KeyEvent.KEYCODE_DPAD_UP -> sound(moveItem(-1), Sfx.MOVE, first)
            KeyEvent.KEYCODE_DPAD_DOWN -> sound(moveItem(1), Sfx.MOVE, first)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> if (first) activate()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> if (first) cats.getOrNull(cat)?.items?.getOrNull(item)?.let { onOptions?.invoke(it) }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (first) {
                val homeIdx = homeIndex()
                if (item > 0) moveItem(-item) else if (cat != homeIdx) focusHome()
                onSound?.invoke(Sfx.BACK) // always answer Back with its sound, even when already home
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Plays [sfx] if the move happened, or a soft bump on the first press against an edge. */
    private fun sound(moved: Boolean, sfx: Sfx, firstPress: Boolean) {
        if (moved) onSound?.invoke(sfx) else if (firstPress) onSound?.invoke(Sfx.EDGE)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            val x = event.getAxisValue(MotionEvent.AXIS_X).let { if (abs(it) > 0.6f) it else event.getAxisValue(MotionEvent.AXIS_HAT_X) }
            val y = event.getAxisValue(MotionEvent.AXIS_Y).let { if (abs(it) > 0.6f) it else event.getAxisValue(MotionEvent.AXIS_HAT_Y) }
            val nx = if (x > 0.6f) 1 else if (x < -0.6f) -1 else 0
            val ny = if (y > 0.6f) 1 else if (y < -0.6f) -1 else 0
            if (nx != 0 && nx != stickX) sound(moveCat(nx), Sfx.CATEGORY, true)
            if (ny != 0 && ny != stickY) sound(moveItem(ny), Sfx.MOVE, true)
            stickX = nx; stickY = ny
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun moveCat(d: Int): Boolean {
        if (cats.isEmpty()) return false
        val next = (cat + d).coerceIn(0, cats.lastIndex)
        if (next == cat) return false
        cats[cat].items.getOrNull(item)?.let { remembered[cats[cat].name] = it.key }
        cat = next
        val key = remembered[cats[cat].name]
        item = cats[cat].items.indexOfFirst { it.key == key }.coerceAtLeast(0)
        itemPos = item.toFloat()
        listFade = 0f
        invalidate()
        return true
    }

    private fun moveItem(d: Int): Boolean {
        val items = cats.getOrNull(cat)?.items ?: return false
        val next = (item + d).coerceIn(0, max(0, items.lastIndex))
        if (next == item) return false
        item = next
        invalidate()
        return true
    }

    private fun activate() {
        val it = cats.getOrNull(cat)?.items?.getOrNull(item) ?: return
        press = 1f
        invalidate()
        onSound?.invoke(Sfx.SELECT)
        onActivate?.invoke(it)
    }

    // ---- Drawing ------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        time += dt

        val k = 1f - exp(-dt / 0.055f)
        catPos += (cat - catPos) * k
        itemPos += (item - itemPos) * k
        val targetTheme = if (cats.getOrNull(cat)?.theme == Theme.GAME) 1f else 0f
        themeT += (targetTheme - themeT) * (1f - exp(-dt / 0.18f))
        listFade = min(1f, listFade + dt / 0.14f)
        press = max(0f, press - dt / 0.2f)

        val w = width.toFloat()
        val h = height.toFloat()
        drawBackground(c, w, h)
        drawWaves(c, w, h)
        drawTopBar(c, w)
        drawCategories(c, w, h)
        drawItems(c, w, h)

        val moving = abs(cat - catPos) > 0.002f || abs(item - itemPos) > 0.002f ||
            abs(targetTheme - themeT) > 0.002f || listFade < 1f || press > 0f
        if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(33) // ribbon keeps drifting at 30 fps
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt(),
        )
    }

    private val gameBg = intArrayOf(0xFF020A1F.toInt(), 0xFF06235A.toInt(), 0xFF0B0230.toInt())
    private val mediaBg = intArrayOf(0xFF120318.toInt(), 0xFF2A0634.toInt(), 0xFF0A0E2E.toInt())
    private val gameWave = intArrayOf(0x9900E5FF.toInt(), 0x80506EFF.toInt(), 0x59A05AFF.toInt())
    private val mediaWave = intArrayOf(0x8CFF5AB4.toInt(), 0x735A8CFF.toInt(), 0x59B45AFF.toInt())
    private val gameGlow = 0xC000E5FF.toInt()
    private val mediaGlow = 0xB3FF50AA.toInt()
    private var bgKey = Float.NaN

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        val q = (themeT * 100).toInt() / 100f
        if (q != bgKey) {
            bgKey = q
            val cols = IntArray(3) { mix(mediaBg[it], gameBg[it], q) }
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.6f, h * 1.3f, cols, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bgPaint)
    }

    // Each ribbon strand is drawn once, in white, into a screen-wide image that repeats seamlessly.
    // Every frame we only slide those images sideways (at different speeds) and tint them,
    // which costs the GPU almost nothing compared with re-drawing glowing curves.
    private class Strand(val amp: Float, val ripple: Int, val phase: Float, val width: Float, val speed: Float)
    private val strands = arrayOf(
        Strand(40f, 3, 0f, 2f, 0.022f),
        Strand(28f, 2, 1.7f, 1.5f, -0.016f),
        Strand(52f, 3, 3.1f, 1f, 0.031f),
    )
    private var strandBitmaps: Array<Bitmap>? = null
    private var strandTops = FloatArray(3)
    private val strandPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        strandBitmaps?.forEach { it.recycle() }
        strandBitmaps = null
        bgKey = Float.NaN
        if (w == 0 || h == 0) return
        val s = w / 640f
        strandBitmaps = Array(strands.size) { i ->
            val st = strands[i]
            // Only as tall as this strand's curve plus its glow; one byte per pixel (alpha only).
            val reach = (st.amp + 8f) * s + st.width * s * 5f
            strandTops[i] = 230f * s - reach
            val bmp = Bitmap.createBitmap(w, (reach * 2f).toInt() + 2, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            c.translate(0f, -strandTops[i])
            wavePath.rewind()
            val tau = (2 * Math.PI).toFloat()
            var x = 0f
            val step = 6f
            while (x <= w + step) {
                val u = x / w
                val y = (230f + sin(u * tau + st.phase) * st.amp + sin(u * tau * st.ripple + st.phase * 2f) * 8f) * s
                if (x == 0f) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                x += step
            }
            waveGlow.color = Color.WHITE
            waveGlow.alpha = 36; waveGlow.strokeWidth = st.width * s * 9f
            c.drawPath(wavePath, waveGlow)
            waveGlow.alpha = 64; waveGlow.strokeWidth = st.width * s * 3.5f
            c.drawPath(wavePath, waveGlow)
            wavePaint.color = Color.WHITE; wavePaint.strokeWidth = st.width * s
            c.drawPath(wavePath, wavePaint)
            bmp
        }
    }

    private fun drawWaves(c: Canvas, w: Float, h: Float) {
        val bitmaps = strandBitmaps ?: return
        for ((i, st) in strands.withIndex()) {
            strandPaint.color = mix(mediaWave[i], gameWave[i], themeT) // ALPHA_8 bitmaps take the paint's color
            var off = (time * st.speed * w) % w
            if (off < 0) off += w
            c.drawBitmap(bitmaps[i], -off, strandTops[i], strandPaint)
            c.drawBitmap(bitmaps[i], w - off, strandTops[i], strandPaint)
        }
    }

    private fun drawTopBar(c: Canvas, w: Float) {
        val now = System.currentTimeMillis()
        if (now - clockStamp > 1000) {
            clockStamp = now
            clockText = clockFmt.format(Date(now)); dateText = dateFmt.format(Date(now))
        }
        boldPaint.textSize = 34f * dp; boldPaint.alpha = 255; boldPaint.textAlign = Paint.Align.RIGHT
        c.drawText(clockText, w - 48f * dp, 62f * dp, boldPaint)
        subPaint.textSize = 15f * dp; subPaint.alpha = 190; subPaint.textAlign = Paint.Align.RIGHT
        c.drawText(dateText, w - 48f * dp, 84f * dp, subPaint)
        boldPaint.textAlign = Paint.Align.LEFT; subPaint.textAlign = Paint.Align.LEFT

        status?.let {
            c.save(); c.translate(48f * dp, 38f * dp); c.scale(0.75f * dp, 0.75f * dp)
            GlyphPainter.draw(c, Glyph.Gamepad, 0xDDFFFFFF.toInt())
            c.restore()
            subPaint.textSize = 15f * dp; subPaint.alpha = 220
            c.drawText(it, 48f * dp + 26f * dp, 53f * dp, subPaint)
        }
    }

    private fun anchorX(w: Float) = w * 0.26f
    private fun catY(h: Float) = h * 0.30f

    private fun drawCategories(c: Canvas, w: Float, h: Float) {
        val ax = anchorX(w); val cy = catY(h)
        val size = 64f * dp; val spacing = 132f * dp
        for ((i, category) in cats.withIndex()) {
            val d = i - catPos
            val x = ax + d * spacing
            if (x < -size || x > w + size) continue
            val near = max(0f, 1f - abs(d))
            val scale = 1f + 0.18f * near
            val alpha = 0.5f + 0.5f * near
            drawIcon(c, x, cy, size * scale, category.colors, category.glyph, Color.WHITE, alpha, focused = false)
            if (near > 0.05f) {
                boldPaint.textSize = 16f * dp; boldPaint.alpha = (255 * near).toInt(); boldPaint.textAlign = Paint.Align.CENTER
                c.drawText(category.name, x, cy + size * scale / 2f + 24f * dp, boldPaint)
                boldPaint.textAlign = Paint.Align.LEFT
            }
        }
    }

    private fun drawItems(c: Canvas, w: Float, h: Float) {
        val items = cats.getOrNull(cat)?.items ?: return
        val ax = anchorX(w); val cy = catY(h)
        val size = 50f * dp; val rowH = 66f * dp
        val top = cy + 118f * dp
        val glow = mix(mediaGlow, gameGlow, themeT)
        for ((i, it) in items.withIndex()) {
            val d = i - itemPos
            val y = top + d * rowH + (1f - listFade) * 18f * dp
            if (y > h + size) break
            val fadeTop = ((y - (cy + 70f * dp)) / (rowH * 0.8f)).coerceIn(0f, 1f)
            if (fadeTop <= 0f) continue
            val near = max(0f, 1f - abs(d))
            val focusedNow = i == item
            val scale = (1f + 0.2f * near) * (1f - 0.08f * press * if (focusedNow) 1f else 0f)
            val alpha = (0.55f + 0.45f * near) * fadeTop * listFade
            drawIcon(c, ax, y, size * scale, it.colors, it.glyph, it.glyphColor, alpha, focused = near > 0.5f, glow = glow)

            val tx = ax + size * scale / 2f + 20f * dp
            val p = if (near > 0.5f) boldPaint else labelPaint
            p.textSize = (18f + 4f * near) * dp; p.alpha = (255 * alpha).toInt()
            if (it.sub != null && near > 0.3f) {
                c.drawText(it.label, tx, y - 2f * dp, p)
                subPaint.textSize = 14f * dp; subPaint.alpha = (200 * alpha * near).toInt()
                c.drawText(it.sub, tx, y + 20f * dp, subPaint)
            } else {
                c.drawText(it.label, tx, y + p.textSize * 0.35f, p)
            }
        }
    }

    private fun iconPaint(colors: IntArray) = iconPaints.getOrPut(colors) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 100f, 100f, colors[0], colors[1], Shader.TileMode.CLAMP)
        }
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, size: Float, colors: IntArray, glyph: Glyph, glyphColor: Int, alpha: Float, focused: Boolean, glow: Int = 0) {
        val half = size / 2f
        val radius = size * 0.28f
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        if (focused) {
            // Soft glow from a few stacked translucent rounded squares (much cheaper than a blur).
            glowPaint.color = glow
            for ((spread, frac) in glowSteps) {
                rect.set(cx - half, cy - half, cx + half, cy + half)
                rect.inset(-spread * dp, -spread * dp)
                glowPaint.alpha = (Color.alpha(glow) * alpha * frac).toInt()
                c.drawRoundRect(rect, radius + spread * dp, radius + spread * dp, glowPaint)
            }
            rect.set(cx - half, cy - half, cx + half, cy + half)
            ringPaint.strokeWidth = 3f * dp; ringPaint.alpha = a
            rect.inset(-3.5f * dp, -3.5f * dp)
            c.drawRoundRect(rect, radius + 3.5f * dp, radius + 3.5f * dp, ringPaint)
        }
        c.save()
        c.translate(cx - half, cy - half)
        c.scale(size / 100f, size / 100f)
        val p = iconPaint(colors); p.alpha = a
        rect.set(0f, 0f, 100f, 100f)
        c.drawRoundRect(rect, 28f, 28f, p)
        c.translate(23f, 23f); c.scale(2.25f, 2.25f)
        GlyphPainter.draw(c, glyph, Color.argb(a * Color.alpha(glyphColor) / 255, Color.red(glyphColor), Color.green(glyphColor), Color.blue(glyphColor)))
        c.restore()
    }
}

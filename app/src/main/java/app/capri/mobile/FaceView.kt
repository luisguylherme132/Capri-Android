package app.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.util.Random

/**
 * Her portrait from face.png with eyes, blink and mouth drawn over it, ported from GameHud.cs.
 * Coordinates below are in face.png pixels; the view shows the crop (146,151) 100x129.
 */
class FaceView(ctx: Context) : View(ctx) {

    companion object {
        private val SKIN = Color.rgb(13, 247, 114)
        private val HAIR = Color.rgb(253, 1, 80)
        private val INK = Color.rgb(0, 0, 6)
        private val IRIS_DEEP = Color.rgb(3, 29, 94)
        private val IRIS = Color.rgb(0, 80, 225)
        private val IRIS_LIT = Color.rgb(9, 105, 251)
        private val SPARK = Color.rgb(248, 250, 254)

        private const val CROP_X = 146
        private const val CROP_Y = 151
        private const val CROP_W = 100
        private const val CROP_H = 129
        const val ASPECT = CROP_H / CROP_W.toFloat()

        private const val EYE_L_X = 170f
        private const val EYE_L_Y = 207.5f
        private const val EYE_R_X = 212.3f
        private const val EYE_R_Y = 198.2f

        private val SOCKET_L = floatArrayOf(161f, 198f, 170f, 196.5f, 177f, 197f, 178.5f, 203f, 181.5f, 208f, 181.5f, 218f, 172f, 219f, 160f, 216f, 159f, 207f)
        private val SOCKET_R = floatArrayOf(205f, 184f, 213f, 177.5f, 230.6f, 178.8f, 230f, 188f, 227f, 198f, 224.5f, 208f, 222f, 212f, 208f, 213f, 195f, 215.5f, 198f, 200f, 201f, 190f)
        private val JAG = floatArrayOf(167.5f, 227f, 173f, 234.5f, 177.5f, 226f, 183.5f, 237f, 190.5f, 223.5f, 195.5f, 234.5f, 202.5f, 221f, 206.5f, 227.5f, 215.5f, 217f)
        private val WEDGE = floatArrayOf(169f, 227.5f, 174f, 233f, 178.5f, 225.5f, 184f, 231f, 190.5f, 223f, 196f, 228.5f, 203f, 220.5f, 208f, 225.5f, 217.5f, 216.5f, 210f, 230f, 196f, 245f, 182f, 240f)
        private val GAPS = floatArrayOf(0f, 2.6f, 5.2f, 8f, 11f)

        /** Mouth shape 0 (shut) to 4 (wide) for a letter, as in Companion.MouthFor. */
        fun mouthFor(letter: Char, step: Int): Int {
            val lower = letter.lowercaseChar()
            if ("aeo".indexOf(lower) >= 0) return if (letter.isUpperCase()) 4 else 3
            if ("iuy".indexOf(lower) >= 0) return 2
            if ("mbp".indexOf(lower) >= 0 || !letter.isLetter()) return 0
            return if (step % 2 == 1) 1 else 2
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint() // no filtering: keeps her pixel art crisp
    private val src = Rect(CROP_X, CROP_Y, CROP_X + CROP_W, CROP_Y + CROP_H)
    private val dst = Rect()
    private val bitmap: Bitmap? = try {
        context.assets.open("sprites/face.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    // state
    private var open = 1f
    private var blinkAt = SystemClock.uptimeMillis() + 2500
    private var blinkStart = 0L
    private var lookX = 0f; private var lookY = 0f
    private var wantX = 0f; private var wantY = 0f
    private var lookAt = 0L
    private var mouth = -1
    private var grinUntil = 0L

    // speech
    private var speech: String? = null
    private var speechStart = 0L
    private var speechMs = 1L
    private var rangeIndex = -1

    private val tick = object : Runnable {
        override fun run() {
            update(SystemClock.uptimeMillis())
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }

    /** Starts moving her mouth along [text], spread over [durationMs]. */
    fun talk(text: String, durationMs: Long) {
        speech = text
        speechStart = SystemClock.uptimeMillis()
        speechMs = durationMs.coerceAtLeast(500)
        rangeIndex = -1
    }

    /** When the speech engine reports which character it is on. */
    fun rangeAt(index: Int) { rangeIndex = index }

    fun hush() { speech = null; mouth = -1 }

    fun grin(ms: Long = 1400) { grinUntil = SystemClock.uptimeMillis() + ms }

    private fun update(now: Long) {
        // blink: 160 ms down and up, every 2.5 to 6 s
        if (blinkStart == 0L && now >= blinkAt) blinkStart = now
        if (blinkStart != 0L) {
            val t = (now - blinkStart) / 160f
            if (t >= 1f) {
                open = 1f; blinkStart = 0L; blinkAt = now + 2500 + rng.nextInt(3500)
            } else open = Math.abs(1f - 2f * t)
        }
        // gaze drifts to a new spot now and then
        if (now >= lookAt) {
            wantX = rng.nextFloat() * 2 - 1; wantY = rng.nextFloat() * 2 - 1
            if (rng.nextInt(3) == 0) { wantX = 0f; wantY = 0f }
            lookAt = now + 900 + rng.nextInt(2200)
        }
        lookX += (wantX - lookX) * 0.25f
        lookY += (wantY - lookY) * 0.25f
        // mouth from the letter being spoken
        val text = speech
        if (text == null) mouth = -1
        else {
            val idx = if (rangeIndex >= 0) rangeIndex else ((now - speechStart).toFloat() / speechMs * text.length).toInt()
            if (idx >= text.length) { speech = null; mouth = -1 }
            else mouth = mouthFor(text[idx.coerceAtLeast(0)], ((now - speechStart) / 80).toInt())
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w).let { if (it == 0) (72 * resources.displayMetrics.density).toInt() else it }
        setMeasuredDimension(width, (width * ASPECT).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, src, dst, bmpPaint)

        val q = width / CROP_W.toFloat()
        fun px(x: Float) = (x - CROP_X) * q
        fun py(y: Float) = (y - CROP_Y) * q

        disc(canvas, INK, px(EYE_L_X), py(EYE_L_Y), 7.2f * q)
        disc(canvas, INK, px(EYE_R_X), py(EYE_R_Y), 9f * q)

        val grin = SystemClock.uptimeMillis() < grinUntil
        watch(canvas, EYE_L_X, EYE_L_Y, 5.6f, 1.3f, SOCKET_L, grin, q)
        watch(canvas, EYE_R_X, EYE_R_Y, 7.2f, 1.7f, SOCKET_R, grin, q)
        if (grin) drawGrin(canvas, q) else if (mouth > 0) lips(canvas, mouth, q)
    }

    private fun disc(c: Canvas, color: Int, x: Float, y: Float, r: Float) {
        paint.style = Paint.Style.FILL; paint.color = color; c.drawCircle(x, y, r, paint)
    }

    private fun polygon(c: Canvas, color: Int, pts: List<Float>) {
        if (pts.size < 6) return
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
        paint.style = Paint.Style.FILL; paint.color = color
        c.drawPath(p, paint)
    }

    private fun toView(flat: FloatArray): List<Float> {
        val out = ArrayList<Float>(flat.size)
        var i = 0
        val q = width / CROP_W.toFloat()
        while (i < flat.size) { out.add((flat[i] - CROP_X) * q); out.add((flat[i + 1] - CROP_Y) * q); i += 2 }
        return out
    }

    private fun blue(c: Canvas, x: Float, y: Float, r: Float, spark: Float) {
        disc(c, IRIS_DEEP, x, y, r)
        disc(c, IRIS, x, y, r * 0.86f)
        disc(c, IRIS_LIT, x, y, r * 0.60f)
        disc(c, SPARK, x - r * 0.06f, y - r * 0.08f, r * spark)
        disc(c, Color.argb(200, 248, 250, 254), x + r * 0.42f, y + r * 0.40f, Math.max(0.8f, r * 0.075f))
    }

    /** Iris that looks around, with lids that close along the tilt of her face. */
    private fun watch(c: Canvas, cx: Float, cy: Float, radius: Float, room: Float, socketFlat: FloatArray, grin: Boolean, q: Float) {
        val x = (cx - CROP_X) * q; val y = (cy - CROP_Y) * q
        blue(c, x + lookX * room * q, y + lookY * room * q * 0.7f, radius * q, 0.34f)
        val shut = 1f - open.coerceIn(0f, 1f)
        if (shut < 0.02f && !grin) return
        val socket = toView(socketFlat)
        val nx = 0.2f; val ny = 0.98f
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        var i = 0
        while (i < socket.size) {
            val along = socket[i] * nx + socket[i + 1] * ny
            top = Math.min(top, along); bottom = Math.max(bottom, along); i += 2
        }
        val tall = bottom - top
        val seam = top + tall * 0.72f
        val upper = top + (seam - top) * shut
        val lower = bottom - (bottom - seam) * Math.max(shut, if (grin) 0.5f else 0f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = Math.max(1.5f, q * 0.35f); paint.color = INK
        if (shut >= 0.02f) {
            val cut = cut(socket, upper, nx, ny, false)
            polygon(c, SKIN, cut.first)
            if (upper > top && cut.second != null) lash(c, cut.second!!)
        }
        if (shut >= 0.02f || grin) {
            val cut = cut(socket, lower, nx, ny, true)
            polygon(c, SKIN, cut.first)
            if (lower < bottom && cut.second != null) lash(c, cut.second!!)
        }
    }

    private fun lash(c: Canvas, ends: FloatArray) {
        paint.style = Paint.Style.STROKE; paint.color = INK
        paint.strokeWidth = Math.max(1.5f, width / CROP_W.toFloat() * 0.35f)
        c.drawLine(ends[0], ends[1], ends[2], ends[3], paint)
        paint.style = Paint.Style.FILL
    }

    /** One side of [shape] cut by the line at distance [lid] along (nx, ny), and the two ends of that cut. */
    private fun cut(shape: List<Float>, lid: Float, nx: Float, ny: Float, under: Boolean): Pair<List<Float>, FloatArray?> {
        val kept = ArrayList<Float>()
        var one: FloatArray? = null; var two: FloatArray? = null
        val n = shape.size / 2
        val sign = if (under) -1f else 1f
        for (i in 0 until n) {
            val hx = shape[i * 2]; val hy = shape[i * 2 + 1]
            val nxp = shape[((i + 1) % n) * 2]; val nyp = shape[((i + 1) % n) * 2 + 1]
            val first = (hx * nx + hy * ny - lid) * sign
            val second = (nxp * nx + nyp * ny - lid) * sign
            if (first <= 0) { kept.add(hx); kept.add(hy) }
            if ((first <= 0) == (second <= 0)) continue
            val t = first / (first - second)
            val cx = hx + (nxp - hx) * t; val cy = hy + (nyp - hy) * t
            kept.add(cx); kept.add(cy)
            if (one != null) two = floatArrayOf(cx, cy) else one = floatArrayOf(cx, cy)
        }
        val ends = if (one != null && two != null) floatArrayOf(one[0], one[1], two[0], two[1]) else null
        return Pair(kept, ends)
    }

    private fun jag(c: Canvas, q: Float, drop: Float, squeeze: Float, wide: Float) {
        val p = Path()
        for (i in 0 until JAG.size / 2) {
            val x = px(192f + (JAG[i * 2] - 192f) * squeeze, q)
            val y = (JAG[i * 2 + 1] + drop - CROP_Y) * q
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE; paint.color = SPARK
        paint.strokeWidth = wide * q; paint.strokeJoin = Paint.Join.MITER; paint.strokeMiter = 6f
        paint.strokeCap = Paint.Cap.SQUARE
        c.drawPath(p, paint)
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun px(x: Float, q: Float) = (x - CROP_X) * q

    private fun lips(c: Canvas, shape: Int, q: Float) {
        val gap = GAPS[shape.coerceIn(0, 4)]
        val m = floatArrayOf(166.5f, 226f, 219f, 215.2f, 215f, 222f, 209f, 230f + gap * 0.7f, 200f, 240f + gap, 184f, 243f + gap, 171f, 238f + gap * 0.7f, 166f, 231f)
        polygon(c, INK, toView(m))
        jag(c, q, 0f, 1f, 3.1f)
        if (gap > 6) {
            val t = floatArrayOf(178f, 233f + gap * 0.62f, 190f, 230f + gap * 0.62f, 204f, 228f + gap * 0.5f, 197f, 234f + gap * 0.74f, 184f, 237f + gap * 0.74f)
            polygon(c, HAIR, toView(t))
        }
        jag(c, q, gap + 1.2f, 0.92f, 3.1f)
    }

    private fun drawGrin(c: Canvas, q: Float) {
        val back = floatArrayOf(166.5f, 226f, 219f, 215.2f, 215f, 222f, 209f, 236f, 198f, 248f, 184f, 248f, 171f, 238f, 166f, 231f)
        polygon(c, INK, toView(back))
        polygon(c, SPARK, toView(WEDGE))
    }
}

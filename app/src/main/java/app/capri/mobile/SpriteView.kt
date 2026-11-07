package app.capri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View

/** One strip-style sprite sheet: [count] frames of 240x384 laid out in a single row. */
class Clip(val file: String, val count: Int, val fps: Int)

object Clips {
    val IDLE = Clip("idle", 16, 8)
    val BLINK = Clip("blink", 4, 8)
    val GLITCH = Clip("glitch", 10, 15)
    val GRIN = Clip("grin", 16, 8)
    val DANCE = Clip("dance", 16, 16)
    val SLEEP = Clip("sleep", 16, 6)
    val WAKE = Clip("wake", 13, 15)
    val ARRIVE = Clip("arrive", 14, 15)
    val LEAVE = Clip("leave", 12, 15)
    val WALK = Clip("walk", 12, 12)
}

class SpriteView(ctx: Context) : View(ctx) {
    companion object {
        const val CELL_W = 240
        const val CELL_H = 384
    }

    // Small LRU so we never hold every sheet in memory at once.
    private val sheets = object : LinkedHashMap<String, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 4
    }
    private var clip = Clips.IDLE
    private var frame = 0
    private var loop = true
    private var onEnd: (() -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val handler = Handler(Looper.getMainLooper())

    var flip = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val step = object : Runnable {
        override fun run() {
            if (frame + 1 < clip.count) frame++
            else if (loop) frame = 0
            else {
                val cb = onEnd
                onEnd = null
                cb?.invoke()
                return
            }
            invalidate()
            handler.postDelayed(this, 1000L / clip.fps)
        }
    }

    fun play(c: Clip, loop: Boolean = true, onEnd: (() -> Unit)? = null) {
        handler.removeCallbacks(step)
        clip = c
        frame = 0
        this.loop = loop
        this.onEnd = onEnd
        invalidate()
        handler.postDelayed(step, 1000L / c.fps)
    }

    fun stop() {
        handler.removeCallbacks(step)
        onEnd = null
    }

    private fun sheet(name: String): Bitmap? = sheets.getOrPut(name) {
        try {
            context.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = sheet(clip.file) ?: return
        src.set(frame * CELL_W, 0, (frame + 1) * CELL_W, CELL_H)
        dst.set(0, 0, width, height)
        if (flip) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}

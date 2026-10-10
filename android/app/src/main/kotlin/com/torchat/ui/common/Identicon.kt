package com.torchat.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.security.MessageDigest

/**
 * A picture of a key.
 *
 * There are no profile photos in a system with no server to upload them to, and
 * a photo would be a claim anyway — anyone can use anyone's face. A TorChat ID
 * is the hash of a signing key, so drawing *that* gives every peer a face that
 * cannot be borrowed: the same ID always draws the same pattern, and a
 * different key draws a different one. It is the visual form of the idea the
 * whole app rests on, that identity is a key and not an account.
 *
 * A 5×5 grid mirrored left to right, coloured from the same hash.
 */
object Identicon {

    private const val GRID = 5

    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** A circular identicon of [sizePx], cached. Safe to call from any thread. */
    fun bitmap(id: String, sizePx: Int): Bitmap {
        val key = "$id@$sizePx"
        cache.get(key)?.let { return it }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), id)
        cache.put(key, bitmap)
        return bitmap
    }

    /** The colour this peer is drawn in, for accents that should match. */
    fun accent(id: String): Int = colours(digest(id)).first

    fun draw(canvas: Canvas, bounds: RectF, id: String) {
        val hash = digest(id)
        val (foreground, background) = colours(hash)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val clip = Path().apply { addOval(bounds, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clip)

        paint.color = background
        canvas.drawRect(bounds, paint)

        // The pattern sits inside a margin so the circle never clips a cell in
        // half — a half-cell reads as a rendering bug, not as part of the face.
        val inset = bounds.width() * 0.2f
        val area = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val cell = area.width() / GRID

        paint.color = foreground
        var bit = 0
        for (column in 0 until (GRID + 1) / 2) {
            for (row in 0 until GRID) {
                val on = (hash[2 + bit / 8].toInt() shr (bit % 8)) and 1 == 1
                bit++
                if (!on) continue

                val top = area.top + row * cell
                drawCell(canvas, paint, area.left + column * cell, top, cell)
                val mirror = GRID - 1 - column
                if (mirror != column) drawCell(canvas, paint, area.left + mirror * cell, top, cell)
            }
        }

        canvas.restore()
    }

    private fun drawCell(canvas: Canvas, paint: Paint, left: Float, top: Float, size: Float) {
        val gap = size * 0.08f
        canvas.drawRoundRect(
            left + gap, top + gap, left + size - gap, top + size - gap,
            size * 0.22f, size * 0.22f, paint,
        )
    }

    private fun colours(hash: ByteArray): Pair<Int, Int> {
        val hue = (((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)) % 360
        val foreground = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.50f, 0.98f))
        val background = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.24f))
        return foreground to background
    }

    private fun digest(id: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
}

/** [Identicon] as a Drawable, for ImageViews and anything else that takes one. */
class IdenticonDrawable(private val id: String) : Drawable() {

    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        Identicon.draw(canvas, rect, id)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

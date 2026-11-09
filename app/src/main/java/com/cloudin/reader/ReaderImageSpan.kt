package com.cloudin.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

internal class ReaderImageSpan(
    asset: ReadingImage,
    private val lineWidth: Int,
    maxHeight: Int,
    private val onReady: () -> Unit
) : ReplacementSpan() {
    private val scale = minOf(1f, lineWidth.toFloat() / asset.width, maxHeight.toFloat() / asset.height)
    private val imageWidth = (asset.width * scale).roundToInt().coerceAtLeast(1)
    private val imageHeight = (asset.height * scale).roundToInt().coerceAtLeast(1)
    private val key = if (maxOf(imageWidth, imageHeight) > 1024) CoverThumbnailLoader.Key(asset.path, 1536)
        else CoverThumbnailLoader.key(asset.path, imageWidth, imageHeight)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint().apply { color = 0x18000000 }
    private val bounds = RectF()
    private val clip = Rect()
    private var requesting = false

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply {
            ascent = -imageHeight
            top = ascent
            descent = 0
            bottom = 0
            leading = 0
        }
        return lineWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val left = x + (lineWidth - imageWidth) / 2f
        bounds.set(left, (y - imageHeight).toFloat(), left + imageWidth, y.toFloat())
        if (!canvas.getClipBounds(clip) || bounds.left >= clip.right || bounds.right <= clip.left ||
            bounds.top >= clip.bottom || bounds.bottom <= clip.top) return
        val result = CoverThumbnailLoader.cached(key)
        val image = result?.bitmap
        if (image != null) {
            imagePaint.alpha = paint.alpha
            canvas.drawBitmap(image, null, bounds, imagePaint)
        } else {
            canvas.drawRect(bounds, placeholder)
            if (result == null && !requesting) {
                requesting = true
                CoverThumbnailLoader.load(key) {
                    requesting = false
                    onReady()
                }
            }
        }
    }
}

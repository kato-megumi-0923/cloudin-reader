package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 照片裁剪视口：铺满视口后按 [zoom] 缩放、按 [panX]/[panY] 平移。
 * 视口本身就是要裁出来的形状，所以不需要额外的裁剪框。
 */
internal class CropView(context: Context) : View(context) {

    var onZoomChanged: ((Float) -> Unit)? = null

    private var source: Bitmap? = null
    private var zoomValue = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = 0x66FFFFFF
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xE6FFFFFF.toInt()
    }
    private val frame = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoomValue * detector.scaleFactor)
            return true
        }
    })

    fun setImage(bitmap: Bitmap) {
        source = bitmap
        zoomValue = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
        onZoomChanged?.invoke(zoomValue)
    }

    fun setZoom(value: Float) {
        val clamped = value.coerceIn(1f, 3f)
        if (clamped == zoomValue) return
        zoomValue = clamped
        clampOffsets()
        invalidate()
        onZoomChanged?.invoke(zoomValue)
    }

    fun currentZoom(): Float = zoomValue

    private fun drawWidth(): Float {
        val bitmap = source ?: return 0f
        return max(width.toFloat(), height.toFloat() * bitmap.width / bitmap.height.toFloat()) * zoomValue
    }

    private fun drawHeight(): Float {
        val bitmap = source ?: return 0f
        return max(height.toFloat(), width.toFloat() * bitmap.height / bitmap.width.toFloat()) * zoomValue
    }

    private fun clampOffsets() {
        val spanX = (drawWidth() - width) / 2f
        val spanY = (drawHeight() - height) / 2f
        offsetX = if (spanX <= 0f) 0f else offsetX.coerceIn(-spanX, spanX)
        offsetY = if (spanY <= 0f) 0f else offsetY.coerceIn(-spanY, spanY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = source ?: return
        val w = drawWidth()
        val h = drawHeight()
        val left = (width - w) / 2f + offsetX
        val top = (height - h) / 2f + offsetY
        frame.set(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, frame, bitmapPaint)

        val thirdW = width / 3f
        val thirdH = height / 3f
        for (index in 1..2) {
            canvas.drawLine(thirdW * index, 0f, thirdW * index, height.toFloat(), gridPaint)
            canvas.drawLine(0f, thirdH * index, width.toFloat(), thirdH * index, gridPaint)
        }
        val tick = 14f * resources.displayMetrics.density
        val inset = 6f * resources.displayMetrics.density
        listOf(
            floatArrayOf(inset, inset, inset + tick, inset),
            floatArrayOf(inset, inset, inset, inset + tick),
            floatArrayOf(width - inset, inset, width - inset - tick, inset),
            floatArrayOf(width - inset, inset, width - inset, inset + tick),
            floatArrayOf(inset, height - inset, inset + tick, height - inset),
            floatArrayOf(inset, height - inset, inset, height - inset - tick),
            floatArrayOf(width - inset, height - inset, width - inset - tick, height - inset),
            floatArrayOf(width - inset, height - inset, width - inset, height - inset - tick)
        ).forEach { canvas.drawLine(it[0], it[1], it[2], it[3], tickPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clampOffsets()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /** 按视口内容生成裁剪结果；[targetWidth] 决定输出宽度，高度按视口比例。 */
    fun crop(targetWidth: Int): Bitmap? {
        val bitmap = source ?: return null
        if (width <= 0 || height <= 0) return null
        val outWidth = targetWidth.coerceAtLeast(1)
        val outHeight = (height.toFloat() * outWidth / width).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val factor = outWidth.toFloat() / width
        canvas.drawColor(Color.BLACK)
        canvas.scale(factor, factor)
        val w = drawWidth()
        val h = drawHeight()
        val left = (width - w) / 2f + offsetX
        val top = (height - h) / 2f + offsetY
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), bitmapPaint)
        return output
    }

    companion object {
        fun targetWidth(viewportWidthDp: Int, viewportHeightDp: Int): Int {
            val longest = max(viewportWidthDp, viewportHeightDp).toFloat()
            return min(1600, max(900, (longest * 1.6f).toInt()))
        }
    }
}

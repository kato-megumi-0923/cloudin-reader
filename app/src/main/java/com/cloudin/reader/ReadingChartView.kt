package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

internal class ReadingChartView(
    context: Context,
    private val millis: LongArray,
    private val labels: Map<Int, String>,
    private val bars: Boolean,
    private val compact: Boolean,
    private val lineCount: Int = millis.size
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path()
    private val dashed = DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)
    private val accent = context.getColor(R.color.shelf_tab_selected)
    private val track = context.getColor(R.color.shelf_track)
    private val labelColor = context.getColor(R.color.shelf_secondary_ink)

    override fun onDraw(canvas: Canvas) {
        val left = if (compact) 0f else 4f * density
        val right = width - left
        val top = if (compact) 3f * density else 14f * density
        val bottom = height - (if (compact) 15f else 27f) * density
        if (bottom <= top || right <= left || millis.isEmpty()) return
        val maximum = max(1f, ceil((millis.maxOrNull() ?: 0) / 60_000f))
        val slots = millis.size
        val step = (right - left) / slots
        fun x(i: Int): Float = if (bars) left + step * (i + 0.5f)
            else left + (right - left) * i / (slots - 1).coerceAtLeast(1)
        fun y(i: Int): Float = bottom - (bottom - top) * (millis[i] / 60_000f / maximum)

        paint.strokeWidth = density * 0.7f
        paint.color = track
        paint.pathEffect = dashed
        if (!compact) {
            for (i in 0..6) {
                val guide = top + (bottom - top) * i / 6f
                canvas.drawLine(left, guide, right, guide, paint)
            }
        } else if (!bars) {
            canvas.drawLine(left, bottom, right, bottom, paint)
        }
        paint.pathEffect = null

        if (bars) {
            val half = minOf(step * 0.24f, (if (compact) 3f else 6f) * density)
            for (i in millis.indices) {
                if (compact) {
                    paint.color = track
                    canvas.drawRect(x(i) - half, top, x(i) + half, bottom, paint)
                }
                if (millis[i] > 0) {
                    paint.color = accent
                    canvas.drawRect(x(i) - half, minOf(y(i), bottom - density), x(i) + half, bottom, paint)
                }
            }
        } else {
            val count = lineCount.coerceIn(0, slots)
            if (count > 0) {
                line.rewind()
                line.moveTo(x(0), y(0))
                for (i in 1 until count) line.lineTo(x(i), y(i))
                paint.color = accent
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f * density
                canvas.drawPath(line, paint)
                paint.style = Paint.Style.FILL
                for (i in 0 until count) {
                    if ((!compact && millis[i] > 0) || (compact && i == count - 1)) {
                        canvas.drawCircle(x(i), y(i), 2.5f * density, paint)
                    }
                }
            }
        }

        paint.textSize = (if (compact) 9f else 11f) * density
        paint.color = labelColor
        labels.forEach { (index, value) ->
            val offset = (x(index) - paint.measureText(value) / 2).coerceIn(left, max(left, right - paint.measureText(value)))
            canvas.drawText(value, offset, height - 3f * density, paint)
        }
        if (!compact) {
            paint.textSize = 9f * density
            canvas.drawText(maximum.toInt().toString(), left + 2f * density, top - 2f * density, paint)
            canvas.drawText(java.text.NumberFormat.getNumberInstance().format(maximum / 2f),
                left + 2f * density, (top + bottom) / 2 - 2f * density, paint)
        }
    }
}

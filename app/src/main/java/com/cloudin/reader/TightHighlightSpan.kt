package com.cloudin.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * 只覆盖字形本身的高亮：默认的 BackgroundColorSpan 会把整行高度都涂上，
 * 行距看起来像一条色带。这里自己画一块从 ascent 到 descent 的底色，
 * 再把文字画回去。
 */
internal class TightHighlightSpan(private val color: Int) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            val metrics = paint.fontMetricsInt
            it.ascent = metrics.ascent
            it.descent = metrics.descent
            it.top = metrics.top
            it.bottom = metrics.bottom
        }
        return ceil(paint.measureText(text, start, end).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val metrics = paint.fontMetrics
        val width = paint.measureText(text, start, end)
        val textColor = paint.color
        paint.color = color
        canvas.drawRect(x, y + metrics.ascent, x + width, y + metrics.descent, paint)
        paint.color = textColor
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
    }
}

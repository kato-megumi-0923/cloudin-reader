package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** 圆角进度条：直接用浮点比例绘制，避免在 layout 回调里改 LayoutParams。 */
internal class RatioBarView(
    context: Context,
    private val ratio: Float
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = context.getColor(R.color.shelf_track)
    private val fill = context.getColor(R.color.shelf_tab_selected)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return
        val r = h / 2f
        paint.color = track
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)
        val filled = width * ratio.coerceIn(0f, 1f)
        if (filled > 0.5f) {
            paint.color = fill
            canvas.drawRoundRect(0f, 0f, filled, h, r, r, paint)
        }
    }
}

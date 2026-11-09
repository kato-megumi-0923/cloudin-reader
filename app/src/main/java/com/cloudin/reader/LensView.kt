package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 拖动时浮起来的"透镜"：半透明白底 + 亮边 + 图标。
 *
 * 不用 AgslGlassView 是有原因的：透镜本身在 root 里，而 AGSL 玻璃要录制背景，
 * 把包含自己的 root 录进 RenderNode 会造成绘制列表自引用（HWUI 递归崩溃）。
 */
class LensView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x8CFFFFFF.toInt()
    }
    private val path = Path()

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        path.rewind()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, ring)
        super.dispatchDraw(canvas)
    }
}

package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 底栏按住后浮起的"放大胶囊"：图标 + 文字一起放大，半透明玻璃底 + 亮边 + 投影。
 *
 * 不用 AGSL 玻璃：透镜自身在 root 里，录制 root 会造成显示列表自引用（HWUI 递归崩溃）。
 */
class LensView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0x99FFFFFF.toInt()
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 让 setShadowLayer 生效
        fill.setShadowLayer(12f * density, 0f, 5f * density, 0x3D000000)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(w, h) / 2f
        path.rewind()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        fill.shader = RadialGradient(
            w * 0.5f, h * 0.18f, maxOf(w, h) * 0.85f,
            intArrayOf(0x8CFFFFFF.toInt(), 0x59FFFFFF, 0x33FFFFFF),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.drawPath(path, ring)
        super.dispatchDraw(canvas)
    }
}

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
 * 底栏按住后浮起的“透镜”：半透明玻璃胶囊 + 亮边 + 投影。
 * 不用 AGSL 玻璃：透镜自身在 root 里，录制 root 会造成显示列表自引用。
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
        strokeWidth = 1.4f * density
        color = 0xA6FFFFFF.toInt()
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 让阴影生效
        fill.setShadowLayer(10f * density, 0f, 4f * density, 0x40000000)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(w, h) / 2f
        path.rewind()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        fill.shader = RadialGradient(
            w * 0.42f, h * 0.32f, maxOf(w, h) * 0.75f,
            intArrayOf(0x73FFFFFF, 0x40FFFFFF, 0x26FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        canvas.drawPath(path, ring)
        super.dispatchDraw(canvas)
    }
}

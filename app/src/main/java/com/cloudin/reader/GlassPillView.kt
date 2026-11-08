package com.cloudin.reader

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 选中胶囊：有清晰但柔和的椭圆轮廓。
 * 由「外层柔光阴影 + 半透明深色玻璃 + 模糊过的亮边」组成，
 * 既能看到选中范围，又不会像硬描边那样割裂。
 */
class GlassPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val inset = 2f * density

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A000000")
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset
        if (right <= left || bottom <= top) return
        val r = minOf(right - left, bottom - top) / 2f

        // 外层柔光，给胶囊一点浮起感
        canvas.drawRoundRect(
            RectF(left + 1f, top + 2f, right - 1f, bottom - 1f),
            r, r, shadowPaint
        )

        // 半透明深色玻璃
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x24000000, 0x1C000000, 0x14000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(left, top, right, bottom), r, r, fillPaint)

        // 亮边：上亮下淡，经过模糊处理，柔和不生硬
        rimPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x59FFFFFF, 0x1AFFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            RectF(left + 0.7f * density, top + 0.7f * density, right - 0.7f * density, bottom - 0.7f * density),
            r, r, rimPaint
        )
    }
}

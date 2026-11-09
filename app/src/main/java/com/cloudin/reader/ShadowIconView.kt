package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.PathParser

/**
 * 顶栏图标：白色矢量 + 和文字图标一致的柔和投影
 * （☰ / ＋ 是文字字形自带阴影，矢量图标需要自己画一层）
 */
internal class ShadowIconView(
    context: Context,
    pathData: String,
    private val boxSize: Float
) : View(context) {
    private val density = resources.displayMetrics.density
    private val path: Path = PathParser.createPathFromPathData(pathData) ?: Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    init {
        // 投影需要软件层，图标很小，开销可忽略
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val scale = size / boxSize
        // 画布会被缩放，阴影半径/偏移也要按同一比例换算，否则会被缩到看不见
        // 与 LiquidGlassButton 给文字加的阴影保持一致：radius 8px / dy 2px
        shadowPaint.setShadowLayer(8f / scale, 0f, 2f / scale, 0xCC000000.toInt())
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f + size)
        canvas.scale(scale, scale)
        canvas.drawPath(path, shadowPaint)
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }
}

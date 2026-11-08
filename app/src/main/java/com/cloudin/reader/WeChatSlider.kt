package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * 仿微信读书的滑条：胶囊形浅色轨道 + 白色圆形滑块（内部显示数值或名称）+ 两端小字。
 */
class WeChatSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var min = 0
    var max = 100
    var value = 50
        set(v) {
            field = v.coerceIn(min, max)
            invalidate()
        }

    var leftText = "A"
    var rightText = "A"
    var thumbText: String? = null
        set(v) {
            field = v
            invalidate()
        }

    var onChange: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackHeight = 30f * density
    private val thumbRadius = 20f * density
    private val innerPad = 6f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0EADB") }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(6f * density, 0f, 2f * density, 0x33000000)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A857D")
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val bigLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A857D")
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
    }
    private val thumbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** 跟随阅读主题调整轨道与文字颜色。 */
    fun applyTheme(theme: Int) {
        if (theme == ReaderPrefs.THEME_NIGHT) {
            trackPaint.color = Color.parseColor("#2F3541")
            thumbPaint.color = Color.parseColor("#3C434F")
            labelPaint.color = Color.parseColor("#8A8A90")
            bigLabelPaint.color = Color.parseColor("#8A8A90")
            thumbTextPaint.color = Color.parseColor("#D6D6DA")
        } else {
            trackPaint.color = Color.parseColor("#F0EADB")
            thumbPaint.color = Color.WHITE
            labelPaint.color = Color.parseColor("#8A857D")
            bigLabelPaint.color = Color.parseColor("#8A857D")
            thumbTextPaint.color = Color.parseColor("#3A3A3A")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (56f * density).toInt())
    }

    private fun trackRect(): RectF {
        val h = trackHeight
        val top = (height - h) / 2f
        return RectF(0f, top, width.toFloat(), top + h)
    }

    private fun trackStart(): Float = thumbRadius
    private fun trackEnd(): Float = width - thumbRadius

    private fun fraction(): Float =
        if (max <= min) 0f else (value - min).toFloat() / (max - min)

    override fun onDraw(canvas: Canvas) {
        val track = trackRect()
        val r = track.height() / 2f
        canvas.drawRoundRect(track, r, r, trackPaint)

        val start = trackStart()
        val end = trackEnd()
        val cx = start + (end - start) * fraction()

        // 两端小字
        val baseY = height / 2f + labelPaint.textSize * 0.36f
        canvas.drawText(leftText, start + 14f * density, baseY, labelPaint)
        canvas.drawText(rightText, end - 18f * density, baseY, bigLabelPaint)

        // 圆形滑块
        canvas.drawCircle(cx, height / 2f, thumbRadius, thumbPaint)
        val text = thumbText ?: value.toString()
        canvas.drawText(text, cx, height / 2f + thumbTextPaint.textSize * 0.36f, thumbTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val start = trackStart()
                val end = trackEnd()
                val t = ((event.x - start) / (end - start)).coerceIn(0f, 1f)
                val newValue = (min + t * (max - min)).roundToInt()
                if (newValue != value) {
                    value = newValue
                    onChange?.invoke(newValue)
                } else {
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}

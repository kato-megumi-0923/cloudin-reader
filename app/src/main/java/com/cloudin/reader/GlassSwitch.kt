package com.cloudin.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 设置页用的开关：半透明轨道 + 白色滑块，开启时轨道染成强调红。
 * 不走 AGSL —— 尺寸太小，采样成本高且容易糊；半透明 + 细描边已经够「玻璃」。
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var checked = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
            contentDescription = if (value) "已开启" else "已关闭"
        }

    /** 深色皮肤下轨道与描边换一套透明白，否则浅底上看不出边界。 */
    var dark = false
        set(value) {
            field = value
            invalidate()
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private var progress = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        color = 0x22000000
    }

    init {
        isClickable = true
        isFocusable = true
    }

    /** 只改状态、不做动画（用于首次绑定）。 */
    fun bind(value: Boolean) {
        checked = value
        progress = if (value) 1f else 0f
        contentDescription = if (value) "已开启" else "已关闭"
        invalidate()
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        checked = !checked
        onCheckedChange?.invoke(checked)
        return super.performClick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(46f).toInt(), dp(28f).toInt())
    }

    private fun animateTo(target: Float) {
        ValueAnimator.ofFloat(progress, target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        val offColor = if (dark) 0x33FFFFFF else 0x24000000
        val onColor = 0xFFD8453F.toInt()
        track.color = blend(offColor, onColor, progress)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, track)

        rim.color = if (dark) 0x40FFFFFF else 0x26000000
        canvas.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, rim)

        val pad = dp(3f)
        val d = h - pad * 2
        val left = pad + (w - pad * 2 - d) * progress
        val box = RectF(left, pad, left + d, pad + d)
        canvas.drawOval(box, thumb)
        canvas.drawOval(box, thumbRim)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

/**
 * 设置页的单选圆点：空心灰圈 → 选中时红圈 + 红色实心点，切换带缩放动画。
 * 由动画进度插值圆环颜色与内点半径，取消选择就是反向播放。
 */
class RadioDot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var dark = false
        set(value) {
            field = value
            invalidate()
        }

    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    private var progress = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)

    private val accent = 0xFFD8453F.toInt()
    private val idleLight = 0xFFB6B1AA.toInt()
    private val idleDark = 0xFF6E7A8C.toInt()

    /** 只设置状态，不播放动画（首次绑定用）。 */
    fun bind(value: Boolean) {
        chosen = value
        progress = if (value) 1f else 0f
        invalidate()
    }

    private fun animateTo(target: Float) {
        ValueAnimator.ofFloat(progress, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(20f).toInt(), dp(20f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(2f)
        val radius = minOf(cx, cy) - stroke / 2f
        ring.color = blend(if (dark) idleDark else idleLight, accent, progress)
        canvas.drawCircle(cx, cy, radius, ring)
        if (progress > 0.01f) {
            inner.color = accent
            canvas.drawCircle(cx, cy, radius * 0.52f * progress, inner)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.widget.ScrollView

/**
 * 手感更软的 ScrollView：
 * - fling 换用更缓的减速曲线，滑到末尾不会「啪」地一下停住
 * - 打开边缘过度滚动（Android 12+ 是拉伸回弹），到顶到底有回弹感
 * - 滚动条常驻，并且可以直接按住拖动（按下时变粗、变实），拖起来是快速定位
 */
class SoftScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val scroller = OverScroller(context, DecelerateInterpolator(2.6f))

    private val density = resources.displayMetrics.density
    /** 滚动条槽宽（系统按这个宽度给 drawable 分配 bounds，我们内部再收窄画出细条） */
    private val barSlot = 10f * density
    private val thumb = CapsuleThumb(density)
    /** 按下的判定宽度：比可见条宽一圈，方便按住 */
    private val hitWidth = 26f * density
    /** 纵向容差，条很短时也能按住 */
    private val hitSlopY = 18f * density

    private var dragging = false
    private var grabOffset = 0f

    /** 滚动条颜色，调用方按当前主题设置。 */
    var indicatorColor: Int = 0x66202828
        set(value) {
            field = value
            thumb.baseColor = value
            invalidate()
        }

    init {
        overScrollMode = View.OVER_SCROLL_ALWAYS
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        scrollBarSize = barSlot.toInt()
        // 用系统滚动条的位置计算，只替换外观：位置由框架维护，滚动时不会错位
        thumb.baseColor = indicatorColor
        setVerticalScrollbarThumbDrawable(thumb)
    }

    override fun fling(velocityY: Int) {
        if (childCount == 0) return
        val viewport = height - paddingTop - paddingBottom
        if (viewport <= 0) return
        val maxScroll = (getChildAt(0).height - viewport).coerceAtLeast(0)
        // 参数顺序：startX, startY, velocityX, velocityY, minX, maxX, minY, maxY
        // startY 必须是当前 scrollY，否则每次惯性滑动都会从顶部重新开始
        scroller.fling(0, scrollY, 0, velocityY, 0, 0, 0, maxScroll)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        // 拖动滚动条期间不接受任何动画，否则会和手指抢位置
        if (dragging) return
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY)
            postInvalidateOnAnimation()
            return
        }
        // 平滑滚动（smoothScrollTo）仍交给父类处理
        super.computeScroll()
    }

    // ---------- 按住滚动条拖动 ----------

    /**
     * 目录的每一行本身是可点击的，按下时事件会先交给行；
     * 所以必须在拦截阶段就判断「按在滚动条上」，否则收不到 ACTION_DOWN。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && beginDrag(ev)) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (beginDrag(ev)) return true
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragTo(ev.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                endDrag()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 按在滑块上就开始拖动；返回 true 表示这次按下被滚动条接管。 */
    private fun beginDrag(ev: MotionEvent): Boolean {
        if (dragging) return true
        val metrics = barMetrics() ?: return false
        if (ev.x < width - hitWidth) return false
        if (ev.y < metrics.thumbTop - hitSlopY) return false
        if (ev.y > metrics.thumbTop + metrics.thumbLength + hitSlopY) return false
        dragging = true
        grabOffset = (ev.y - metrics.thumbTop).coerceIn(0f, metrics.thumbLength)
        thumb.pressed = true
        // 惯性滑动停掉；smoothScrollTo 的动画会在 computeScroll 里被跳过（drag 期间），
        // 松手时再重定向到当时的位置
        scroller.forceFinished(true)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        return true
    }

    private fun endDrag() {
        dragging = false
        thumb.pressed = false
        // 把残留的平滑滚动动画重定向到当前位置，否则松手后会跳回按下时的位置
        smoothScrollTo(0, scrollY)
        invalidate()
    }

    private fun dragTo(touchY: Float) {
        val metrics = barMetrics() ?: return
        val travel = metrics.trackLength - metrics.thumbLength
        if (travel <= 0f) return
        val ratio = ((touchY - grabOffset - metrics.trackTop) / travel).coerceIn(0f, 1f)
        scrollTo(0, (ratio * maxScrollY()).toInt())
        invalidate()
    }

    private fun maxScrollY(): Int {
        val child = getChildAt(0) ?: return 0
        val viewport = height - paddingTop - paddingBottom
        return (child.height - viewport).coerceAtLeast(0)
    }

    /** 滑块在屏幕上的位置，算法与系统滚动条一致（行程 = 轨道长 - 滑块长）。 */
    private fun barMetrics(): BarMetrics? {
        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        val offset = computeVerticalScrollOffset()
        if (extent <= 0 || range <= extent) return null
        val trackTop = paddingTop.toFloat()
        val trackLength = (height - paddingTop - paddingBottom).toFloat()
        if (trackLength <= 0f) return null
        // 系统滚动条的最短长度是 2 倍槽宽，这里保持一致，按压判定才不会和视觉错位
        val minThumb = barSlot * 2f
        val thumbLength = (trackLength * extent / range)
            .coerceAtLeast(minThumb)
            .coerceAtMost(trackLength)
        val travel = (trackLength - thumbLength).coerceAtLeast(0f)
        val maxOffset = (range - extent).coerceAtLeast(1)
        val thumbTop = trackTop + travel * offset / maxOffset
        return BarMetrics(trackTop, trackLength, thumbTop, thumbLength)
    }

    private class BarMetrics(
        val trackTop: Float,
        val trackLength: Float,
        val thumbTop: Float,
        val thumbLength: Float
    )

    /**
     * 胶囊滑块：系统按整个槽宽给它 bounds，这里用左右内缩控制粗细。
     * 不按下时是 4dp 细条，按下后铺开到 8dp 并提高不透明度。
     */
    private class CapsuleThumb(private val density: Float) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var baseColor: Int = 0x66202828

        var pressed: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val inset = if (pressed) density else 3f * density
            val left = bounds.left + inset
            val right = bounds.right - inset
            if (right <= left) return
            val radius = (right - left) / 2f
            paint.color = baseColor
            // 用自身透明度控制浓淡，按下时更实，一眼能看出被按住
            paint.alpha = if (pressed) 235 else 120
            canvas.drawRoundRect(left, bounds.top.toFloat(), right, bounds.bottom.toFloat(), radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) = Unit // 淡入淡出已关闭，浓淡由按下状态决定

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

package com.cloudin.reader

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.widget.ScrollView

/**
 * 手感更软的 ScrollView：
 * - fling 换用更缓的减速曲线，滑到末尾不会「啪」地一下停住
 * - 打开边缘过度滚动（Android 12+ 是拉伸回弹），到顶到底有回弹感
 * - 滚动条常驻
 */
class SoftScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val scroller = OverScroller(context, DecelerateInterpolator(2.6f))

    init {
        overScrollMode = View.OVER_SCROLL_ALWAYS
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        scrollBarSize = (4 * resources.displayMetrics.density).toInt()
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
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY)
            postInvalidateOnAnimation()
            return
        }
        // 平滑滚动（smoothScrollTo）仍交给父类处理
        super.computeScroll()
    }
}

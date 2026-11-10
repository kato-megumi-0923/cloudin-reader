package com.cloudin.reader

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Continuous scroll position within the current block, plus drag and fling handling. */
internal class ScrollController(
    private val paginator: Paginator,
    private val viewportHeight: () -> Float,
    private val touchSlop: Int,
    private val onScroll: (Float) -> Unit,
    private val handleTap: (Float, Float) -> Unit
) {
    var withinBlock = 0f
        private set

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastScrollY = 0f
    private var scrollDragging = false
    private var velocity: VelocityTracker? = null
    private var scrollFling: ValueAnimator? = null
    private var scrollLimit: Pair<Int, Float>? = null

    fun stop() {
        scrollFling?.cancel()
        scrollFling = null
        velocity?.recycle()
        velocity = null
        scrollDragging = false
    }

    fun resetLimit() {
        scrollLimit = null
    }

    fun seek(index: Int, shift: Float): Int {
        withinBlock = shift
        return clampScrollEnd(index)
    }

    fun isAtEnd(index: Int): Boolean {
        val (endIndex, shift) = scrollEnd()
        return index >= endIndex && (index > endIndex || withinBlock >= shift - 0.5f)
    }

    fun advance(index: Int, distance: Float): Int {
        val columns = paginator.columns
        var pageIndex = index
        withinBlock += distance
        while (withinBlock < 0f && pageIndex > 0) {
            pageIndex -= columns
            withinBlock += paginator.blockHeight(pageIndex)
        }
        withinBlock = withinBlock.coerceAtLeast(0f)
        while (pageIndex + columns < paginator.pages.size) {
            val height = paginator.blockHeight(pageIndex)
            if (withinBlock < height) break
            withinBlock -= height
            pageIndex += columns
        }
        return clampScrollEnd(pageIndex)
    }

    fun positionAt(index: Int, y: Float): Pair<Int, Float> {
        var block = index
        var localY = y + withinBlock
        while (block + paginator.columns < paginator.pages.size) {
            val height = paginator.blockHeight(block)
            if (localY < height) break
            localY -= height
            block += paginator.columns
        }
        return block to localY
    }

    inline fun forEachVisibleBlock(index: Int, draw: (Int, Float) -> Unit) {
        var block = index
        var top = -withinBlock
        val height = viewportHeight()
        while (block < paginator.pages.size && top < height) {
            draw(block, top)
            top += paginator.blockHeight(block)
            block += paginator.columns
        }
    }

    private fun scrollEnd(): Pair<Int, Float> {
        scrollLimit?.let { return it }
        val columns = paginator.columns
        var index = (paginator.pages.lastIndex / columns) * columns
        var shift = paginator.blockHeight(index) - viewportHeight()
        while (shift < 0 && index > 0) {
            index -= columns
            shift += paginator.blockHeight(index)
        }
        return (index to shift.coerceAtLeast(0f)).also { scrollLimit = it }
    }

    private fun clampScrollEnd(index: Int): Int {
        val (endIndex, endShift) = scrollEnd()
        if (index > endIndex || (index == endIndex && withinBlock > endShift)) {
            withinBlock = endShift
            return endIndex
        }
        return index
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollFling?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                touchDownX = event.x
                touchDownY = event.y
                lastScrollY = event.y
                scrollDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (scrollDragging || abs(event.y - touchDownY) > touchSlop) {
                    scrollDragging = true
                    onScroll(lastScrollY - event.y)
                    lastScrollY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (scrollDragging) {
                        velocity?.addMovement(event)
                        velocity?.computeCurrentVelocity(1000, 6000f)
                        val speed = -(velocity?.yVelocity ?: 0f)
                        if (abs(speed) > 200f) {
                            var previous = 0f
                            scrollFling = ValueAnimator.ofFloat(0f, speed * 0.22f).apply {
                                duration = 420L
                                interpolator = DecelerateInterpolator()
                                addUpdateListener {
                                    val value = it.animatedValue as Float
                                    onScroll(value - previous)
                                    previous = value
                                }
                                start()
                            }
                        }
                    } else if (abs(event.x - touchDownX) <= touchSlop) {
                        handleTap(event.x, event.y)
                    }
                }
                velocity?.recycle()
                velocity = null
                scrollDragging = false
            }
        }
        return true
    }
}

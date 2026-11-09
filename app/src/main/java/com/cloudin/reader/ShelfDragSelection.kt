package com.cloudin.reader

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A drag starting on a book only adds to the current selection. */
internal class ShelfDragSelection(
    private val recycler: RecyclerView,
    private val adapter: BookshelfAdapter
) : RecyclerView.SimpleOnItemTouchListener() {
    private val touchSlop = ViewConfiguration.get(recycler.context).scaledTouchSlop
    private val density = recycler.resources.displayMetrics.density
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    private val edgeScroll = object : Runnable {
        override fun run() {
            if (!dragging || !adapter.selecting || !recycler.isAttachedToWindow) return
            val step = edgeStep()
            if (step == 0 || !recycler.canScrollVertically(step)) return
            recycler.scrollBy(0, step)
            val y = lastY.coerceIn(0f, (recycler.height - 1).coerceAtLeast(0).toFloat())
            selectSegment(lastX, y, lastX, y)
            recycler.postOnAnimation(this)
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                val child = rv.findChildViewUnder(event.x, event.y) ?: return false
                if (!adapter.isBookPosition(rv.getChildAdapterPosition(child))) return false
                pointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!adapter.selecting || pointerId == MotionEvent.INVALID_POINTER_ID) return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || hypot(event.getX(index) - lastX, event.getY(index) - lastY) <= touchSlop) return false
                dragging = true
                rv.stopScroll()
                selectThrough(event, index)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) reset()
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (!dragging || !adapter.selecting) {
            reset()
            return
        }
        val index = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) selectThrough(event, index)
            MotionEvent.ACTION_UP -> {
                if (index >= 0) selectThrough(event, index)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) reset()
        }
    }

    private fun selectThrough(event: MotionEvent, index: Int) {
        for (history in 0 until event.historySize) {
            advance(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
        }
        advance(event.getX(index), event.getY(index))
        recycler.removeCallbacks(edgeScroll)
        if (edgeStep() != 0) recycler.postOnAnimation(edgeScroll)
    }

    private fun advance(x: Float, y: Float) {
        selectSegment(lastX, lastY, x, y)
        lastX = x
        lastY = y
    }

    private fun selectSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val positions = ArrayList<Int>()
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            val position = recycler.getChildAdapterPosition(child)
            if (adapter.isBookPosition(position) && intersects(child, x0, y0, x1, y1)) positions.add(position)
        }
        adapter.selectPassedBooks(positions)
    }

    private fun intersects(view: View, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        var entry = 0f
        var exit = 1f
        fun clip(start: Float, end: Float, low: Float, high: Float): Boolean {
            val delta = end - start
            if (delta == 0f) return start in low..high
            val a = (low - start) / delta
            val b = (high - start) / delta
            entry = maxOf(entry, minOf(a, b))
            exit = minOf(exit, maxOf(a, b))
            return entry <= exit
        }
        return clip(x0, x1, view.x, view.x + view.width) && clip(y0, y1, view.y, view.y + view.height)
    }

    private fun edgeStep(): Int {
        if (lastX !in 0f..recycler.width.toFloat()) return 0
        val band = 52f * density
        val fraction = when {
            lastY < band -> (lastY - band) / band
            lastY > recycler.height - band -> (lastY - recycler.height + band) / band
            else -> 0f
        }
        return (fraction.coerceIn(-1f, 1f) * 12f * density).roundToInt()
    }

    private fun reset() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        recycler.removeCallbacks(edgeScroll)
    }
}

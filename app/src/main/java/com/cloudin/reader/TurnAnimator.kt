package com.cloudin.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Paged gestures and transitions. A page changes only when its turn commits. */
internal class TurnAnimator(
    private val view: View,
    private val dp: Float,
    private val touchSlop: Int,
    private val currentPage: () -> Int,
    private val targetPage: (direction: Int) -> Int?,
    private val onPageCommitted: (Int) -> Unit,
    private val handleTap: (Float, Float) -> Unit,
    private val renderPage: (Canvas, Int, Float, Float, Boolean, Float) -> Unit,
    private val drawPageBackdrop: (Canvas) -> Unit
) {
    var mode = ReaderPrefs.MODE_CURL
    var animating = false
        private set

    private val width get() = view.width
    private val height get() = view.height
    private val curl = PageCurl()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animProgress = 0f
    private var animTarget = 0
    // In the middle third, grasp the side of the sheet instead of a corner.
    private var curlCornerY: Float? = null
    private var curlTipX = 0f
    private var curlTipY = 0f
    private var animator: ValueAnimator? = null

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var dragActive = false
    private var swipeHandled = false

    fun stop() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        animating = false
        dragActive = false
        animProgress = 0f
    }

    private fun commitPage(index: Int) {
        animating = false
        animProgress = 0f
        onPageCommitted(index)
    }

    fun turn(delta: Int, y: Float = touchDownY) {
        if (animating) return
        val target = targetPage(delta) ?: return
        if (mode == ReaderPrefs.MODE_NONE) {
            commitPage(target)
            return
        }

        animTarget = target
        animating = true
        animProgress = 0f
        if (mode == ReaderPrefs.MODE_CURL) {
            beginCurl(y)
            settleCurl(commit = true, duration = 340L)
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    commitPage(animTarget)
                }
            })
            start()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDownTime = event.eventTime
                dragActive = false
                swipeHandled = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeHandled) return true
                if (!dragActive && !animating) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (mode == ReaderPrefs.MODE_NONE) {
                        if (abs(dx) > 56f * dp && abs(dx) > abs(dy) * 1.4f) {
                            turn(if (dx < 0) 1 else -1)
                            swipeHandled = true
                        }
                        return true
                    }
                    val vertical = mode == ReaderPrefs.MODE_VERTICAL
                    val canTurn = if (vertical) {
                        abs(dy) > touchSlop && abs(dy) > abs(dx)
                    } else if (mode == ReaderPrefs.MODE_CURL) {
                        abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.65f
                    } else {
                        abs(dx) > 16f * dp && abs(dy) < 90f * dp
                    }
                    if (canTurn) {
                        val dir = if ((if (vertical) dy else dx) < 0) 1 else -1
                        val target = targetPage(dir)
                        if (target != null) {
                            dragActive = true
                            animTarget = target
                            if (mode == ReaderPrefs.MODE_CURL) beginCurl(touchDownY)
                            animating = true
                            animProgress = 0f
                        }
                    }
                }
                if (dragActive) {
                    updateDrag(event.x, event.y)
                    view.invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeHandled) return true
                if (dragActive) {
                    dragActive = false
                    val dt2 = event.eventTime - touchDownTime
                    updateDrag(event.x, event.y)
                    val travel = dragTravel(event.x, event.y)
                    // 快速滑动（甩动）即使距离不够也完成翻页
                    val flung = travel > 56f * dp && dt2 < 280
                    settleDrag(travel > turnDistance() * 0.32f || flung)
                    return true
                }
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val dt = event.eventTime - touchDownTime
                if (abs(dx) < 12f * dp && abs(dy) < 12f * dp && dt < 450) {
                    handleTap(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragActive) {
                    dragActive = false
                    settleDrag(false)
                }
                return true
            }
        }
        return true
    }

    private fun beginCurl(y: Float) {
        curlCornerY = when {
            y < height / 3f -> 0f
            y > height * 2f / 3f -> height.toFloat()
            else -> null
        }
        curlTipX = curlEdgeX()
        curlTipY = curlCornerY ?: y.coerceIn(0f, height.toFloat())
    }

    private fun curlEdgeX(): Float = if (animTarget > currentPage()) width.toFloat() else 0f

    private fun turnDistance() = if (mode == ReaderPrefs.MODE_VERTICAL) height else width

    private fun dragTravel(x: Float, y: Float): Float {
        val distance = if (mode == ReaderPrefs.MODE_VERTICAL) y - touchDownY else x - touchDownX
        return if (animTarget > currentPage()) -distance else distance
    }

    private fun updateDrag(x: Float, y: Float) {
        if (mode == ReaderPrefs.MODE_CURL) {
            curlTipX = x.coerceIn(0f, width.toFloat())
            curlTipY = y.coerceIn(0f, height.toFloat())
        } else {
            animProgress = (dragTravel(x, y) / turnDistance().coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    private fun settleCurl(commit: Boolean, duration: Long) {
        animator?.cancel()
        val edgeX = curlEdgeX()
        val destinationX = if (commit) (if (animTarget > currentPage()) -width.toFloat() else 2f * width) else edgeX
        val fromX = curlTipX
        val fromY = curlTipY
        val destinationY = curlCornerY ?: fromY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                curlTipX = fromX + (destinationX - fromX) * fraction
                curlTipY = fromY + (destinationY - fromY) * fraction
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (commit) commitPage(animTarget) else {
                        animating = false
                        view.invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun settleDrag(commit: Boolean) {
        if (mode == ReaderPrefs.MODE_CURL) {
            settleCurl(commit, if (commit) 260L else 200L)
            return
        }
        animator?.cancel()
        val from = animProgress
        val to = if (commit) 1f else 0f
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (170 * abs(to - from)).toInt().coerceIn(60, 220).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (commit) {
                        commitPage(animTarget)
                    } else {
                        animating = false
                        animProgress = 0f
                        view.invalidate()
                    }
                }
            })
            start()
        }
    }

    fun draw(canvas: Canvas) {
        if (mode == ReaderPrefs.MODE_CURL) drawCurl(canvas) else drawTransition(canvas)
    }

    private fun drawTransition(canvas: Canvas) {
        val from = currentPage()
        val to = animTarget
        val p = animProgress
        val forward = to > from
        val w = width.toFloat()

        when (mode) {
            ReaderPrefs.MODE_SLIDE -> {
                val dir = if (forward) 1f else -1f
                drawPage(canvas, from, -dir * p * w, 1f)
                drawPage(canvas, to, dir * (1f - p) * w, 1f)
            }
            ReaderPrefs.MODE_VERTICAL -> {
                val dir = if (forward) 1f else -1f
                drawPage(canvas, from, 0f, 1f, dy = -dir * p * height)
                drawPage(canvas, to, 0f, 1f, dy = dir * (1f - p) * height)
            }
            else -> drawPage(canvas, from, 0f, 1f)
        }
    }

    private fun drawCurl(canvas: Canvas) {
        val edgeX = curlEdgeX()
        val anchorY = curlCornerY ?: curlTipY
        val distance = abs(edgeX - curlTipX)
        val progress = distance / (2f * width)
        if ((distance < 0.01f && abs(anchorY - curlTipY) < 0.01f) || progress >= 1f) {
            drawPage(canvas, if (progress >= 1f) animTarget else currentPage(), 0f, 1f)
            return
        }

        curl.update(width.toFloat(), height.toFloat(), edgeX, anchorY, curlTipX, curlTipY)
        val shadeStrength = minOf(progress * 12f, (1f - progress) * 8f, 1f)
        drawPage(canvas, animTarget, 0f, 1f)
        // 折痕投在下一页上的软阴影：更宽更实，避开"纸片悬浮"的观感
        drawCreaseGradient(canvas, 14f * dp, Color.argb((26 * shadeStrength).toInt(), 0, 0, 0), Color.TRANSPARENT)
        drawCreaseGradient(canvas, 64f * dp, Color.argb((64 * shadeStrength).toInt(), 0, 0, 0), Color.TRANSPARENT)

        val front = canvas.save()
        canvas.clipOutPath(curl.exposed)
        drawPage(canvas, currentPage(), 0f, 1f)
        canvas.restoreToCount(front)

        val back = canvas.save()
        canvas.clipPath(curl.paperBack)
        val reflected = canvas.save()
        canvas.concat(curl.reflection)
        drawPageBackdrop(canvas)
        drawPage(canvas, currentPage(), 0f, 0.13f, opaque = false)
        canvas.restoreToCount(reflected)
        // The paper catches light on the bend and darkens on the reverse side.
        drawCreaseGradient(
            canvas, -42f * dp,
            Color.argb((35 * shadeStrength).toInt(), 255, 255, 255), Color.argb(28, 0, 0, 0)
        )
        canvas.restoreToCount(back)
    }

    private fun drawCreaseGradient(canvas: Canvas, distance: Float, start: Int, end: Int) {
        foldPaint.shader = LinearGradient(
            curl.creaseX, curl.creaseY,
            curl.creaseX + curl.normalX * distance, curl.creaseY + curl.normalY * distance,
            start, end, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), foldPaint)
        foldPaint.shader = null
    }

    private fun drawPage(
        canvas: Canvas, index: Int, dx: Float, alpha: Float, opaque: Boolean = true, dy: Float = 0f
    ) {
        renderPage(canvas, index, dx, alpha, opaque, dy)
    }
}

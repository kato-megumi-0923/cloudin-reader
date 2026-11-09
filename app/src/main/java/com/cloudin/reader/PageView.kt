package com.cloudin.reader

import android.annotation.SuppressLint

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 分页阅读视图：排版、翻页动画、页眉页脚。
 * 翻页动画支持：无、平移、仿真、淡入。
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCenterTap: (() -> Unit)? = null
    var onPageChanged: ((pageIndex: Int, pageCount: Int, progress: Float) -> Unit)? = null
    var onChapterChanged: ((String) -> Unit)? = null

    private var document: Document? = null
    private var pageText: CharSequence = ""

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var padH = 20f * dp
    private val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val padTop = (if (landscape) 18f else 48f) * dp
    private var bottomInset = 0
    private val padBottom get() = 36f * dp + bottomInset

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("serif", Typeface.NORMAL)
        isSubpixelText = true
        textSize = 19f * resources.displayMetrics.scaledDensity
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * resources.displayMetrics.scaledDensity
    }
    private val curl = PageCurl()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = ReaderPrefs.background(ReaderPrefs.THEME_PAPER) }

    private var pages: List<IntRange> = emptyList()
    private var pageIndex = 0
    private var pendingOffset = 0
    private val layoutCache = LruCache<Int, StaticLayout>(4)
    private var lineMultiplier = PageBreaker.LINE_MULTIPLIER

    private var theme = ReaderPrefs.THEME_PAPER
    private var turnMode = ReaderPrefs.MODE_CURL
    internal val usesCurl get() = turnMode == ReaderPrefs.MODE_CURL

    private var bgColor = ReaderPrefs.background(theme)
    private var inkColor = ReaderPrefs.ink(theme)
    private var softColor = ReaderPrefs.softInk(theme)

    private var animating = false
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

    init {
        isClickable = true
    }

    fun setTheme(newTheme: Int) {
        theme = newTheme
        bgColor = ReaderPrefs.background(theme)
        inkColor = ReaderPrefs.ink(theme)
        softColor = ReaderPrefs.softInk(theme)
        bodyPaint.color = inkColor
        bgPaint.color = bgColor
        layoutCache.evictAll()
        invalidate()
    }

    fun setTurnMode(mode: Int) {
        turnMode = mode
    }

    fun setBottomInset(inset: Int) {
        if (bottomInset == inset) return
        bottomInset = inset
        repaginate()
    }

    /** 页边距，0–100，映射到 12–44dp。 */
    fun setMargin(percent: Int) {
        padH = dpScale(12f + percent.coerceIn(0, 100) * 0.32f)
        repaginate()
    }

    /** 行距，0–100，映射到 1.25–2.05 倍。 */
    fun setLineSpacing(percent: Int) {
        lineMultiplier = 1.25f + percent.coerceIn(0, 100) / 100f * 0.8f
        repaginate()
    }

    private fun dpScale(v: Float): Float = v * dp

    private fun repaginate() {
        pendingOffset = currentOffset()
        layoutCache.evictAll()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun setFontSize(sizeSp: Float) {
        bodyPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        val keep = currentOffset()
        pendingOffset = keep
        layoutCache.evictAll()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun setDocument(doc: Document, startOffset: Int) {
        document = doc
        pendingOffset = startOffset
        pageIndex = 0
        layoutCache.evictAll()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun currentOffset(): Int {
        val d = document ?: return 0
        val p = pages.getOrNull(pageIndex) ?: return 0
        return min(p.first, d.text.length)
    }

    fun seekToOffset(offset: Int) {
        val d = document ?: return
        if (pages.isEmpty()) {
            pendingOffset = offset
            return
        }
        val target = offset.coerceIn(0, maxOf(0, d.text.length - 1))
        val idx = pages.indexOfLast { it.first <= target }
        if (idx >= 0 && idx != pageIndex) {
            pageIndex = idx
            invalidate()
            notifyChanged()
            pendingOffset = currentOffset()
        }
    }

    fun nextPage(y: Float = height / 2f) = turn(1, y)
    fun prevPage(y: Float = height / 2f) = turn(-1, y)
    fun pageCount(): Int = pages.size
    fun pageIndex(): Int = pageIndex

    private fun turn(delta: Int, y: Float = touchDownY) {
        if (document == null) return
        if (pages.isEmpty() || animating) return
        val target = pageIndex + delta
        if (target !in pages.indices) return

        if (turnMode == ReaderPrefs.MODE_NONE || pages.size <= 1) {
            applyPage(target)
            return
        }

        animTarget = target
        animating = true
        animProgress = 0f
        if (turnMode == ReaderPrefs.MODE_CURL) {
            beginCurl(y)
            settleCurl(commit = true, duration = 340L)
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (turnMode) {
                ReaderPrefs.MODE_FADE -> 220L
                ReaderPrefs.MODE_SLIDE -> 260L
                else -> 340L
            }
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyPage(animTarget)
                }
            })
            start()
        }
        pendingOffset = currentOffset()
    }

    private fun applyPage(index: Int) {
        pageIndex = index.coerceIn(0, maxOf(0, pages.size - 1))
        animating = false
        animProgress = 0f
        invalidate()
        notifyChanged()
        pendingOffset = currentOffset()
    }

    private fun notifyChanged() {
        val d = document ?: return
        val p = pages.getOrNull(pageIndex) ?: return
        val progress = progressAt(pageIndex)
        onPageChanged?.invoke(pageIndex, pages.size, progress)
        onChapterChanged?.invoke(d.chapterAt(p.first))
    }

    private fun progressAt(index: Int): Float {
        val d = document ?: return 0f
        val page = pages.getOrNull(index) ?: return 0f
        return if (index == pages.lastIndex) 1f else page.first.toFloat() / d.text.length
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) paginate()
    }

    private fun paginate() {
        val d = document ?: return
        val w = (width - padH * 2).toInt()
        val h = (height - padTop - padBottom).toInt()
        if (w <= 0 || h <= 0) return
        pageText = if (d.images.isEmpty()) d.text else SpannableString(d.text).apply {
            d.images.forEach { image ->
                setSpan(ReaderImageSpan(image.asset, w, h) { invalidate() },
                    image.offset, image.offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        pages = PageBreaker.breakPages(pageText, bodyPaint, w, h, lineMultiplier)
        layoutCache.evictAll()
        if (pages.isEmpty()) return
        val target = pendingOffset.coerceIn(0, d.text.length)
        val idx = pages.indexOfLast { it.first <= target }
        pageIndex = if (idx < 0) 0 else idx
        notifyChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                if (!dragActive && pages.isNotEmpty() && !animating) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (turnMode == ReaderPrefs.MODE_NONE) {
                        if (abs(dx) > 56f * dp && abs(dx) > abs(dy) * 1.4f) {
                            turn(if (dx < 0) 1 else -1)
                            swipeHandled = true
                        }
                        return true
                    }
                    val horizontal = if (turnMode == ReaderPrefs.MODE_CURL) {
                        abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.65f
                    } else {
                        abs(dx) > 16f * dp && abs(dy) < 90f * dp
                    }
                    if (horizontal) {
                        val dir = if (dx < 0) 1 else -1
                        val target = pageIndex + dir
                        if (target in pages.indices) {
                            dragActive = true
                            animTarget = target
                            if (turnMode == ReaderPrefs.MODE_CURL) beginCurl(touchDownY)
                            animating = true
                            animProgress = 0f
                        }
                    }
                }
                if (dragActive) {
                    updateDrag(event.x, event.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeHandled) return true
                if (dragActive) {
                    dragActive = false
                    val dt2 = event.eventTime - touchDownTime
                    updateDrag(event.x, event.y)
                    val travel = dragTravel(event.x)
                    // 快速滑动（甩动）即使距离不够也完成翻页
                    val flung = travel > 56f * dp && dt2 < 280
                    settleDrag(travel > width * 0.32f || flung)
                    return true
                }
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val dt = event.eventTime - touchDownTime
                if (abs(dx) < 12f * dp && abs(dy) < 12f * dp && dt < 450) {
                    val w = width.toFloat()
                    when {
                        event.x < w * 0.32f -> turn(-1)
                        event.x > w * 0.68f -> turn(1)
                        else -> onCenterTap?.invoke()
                    }
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

    private fun curlEdgeX(): Float = if (animTarget > pageIndex) width.toFloat() else 0f

    private fun dragTravel(x: Float): Float {
        val dx = x - touchDownX
        return if (turnMode != ReaderPrefs.MODE_CURL) abs(dx)
        else if (animTarget > pageIndex) -dx else dx
    }

    private fun updateDrag(x: Float, y: Float) {
        if (turnMode == ReaderPrefs.MODE_CURL) {
            curlTipX = x.coerceIn(0f, width.toFloat())
            curlTipY = y.coerceIn(0f, height.toFloat())
        } else {
            animProgress = (dragTravel(x) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    private fun settleCurl(commit: Boolean, duration: Long) {
        animator?.cancel()
        val edgeX = curlEdgeX()
        val destinationX = if (commit) (if (animTarget > pageIndex) -width.toFloat() else 2f * width) else edgeX
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
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (commit) applyPage(animTarget) else {
                        animating = false
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun settleDrag(commit: Boolean) {
        if (turnMode == ReaderPrefs.MODE_CURL) {
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
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (commit) {
                        applyPage(animTarget)
                    } else {
                        animating = false
                        animProgress = 0f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (animating && turnMode == ReaderPrefs.MODE_CURL && pages.isNotEmpty() && document != null) {
            drawCurl(canvas)
            return
        }
        drawBackground(canvas)
        if (pages.isEmpty() || document == null) return
        if (!animating) {
            drawPage(canvas, pageIndex, 0f, 1f)
        } else {
            drawTransition(canvas)
        }
    }

    private var paperBitmap: Bitmap? = null
    private val paperBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)



    private fun drawPageBackdrop(canvas: Canvas, dx: Float) {
        if (theme == ReaderPrefs.THEME_PAPER) {
            val bmp = paperBitmap ?: runCatching {
                BitmapFactory.decodeResource(resources, R.drawable.paper_bg)
            }.getOrNull()?.also { paperBitmap = it }
            if (bmp != null) {
                paperBitmapPaint.alpha = 255
                canvas.drawBitmap(bmp, null, RectF(dx, 0f, dx + width, height.toFloat()), paperBitmapPaint)
                return
            }
        }
        canvas.drawRect(dx, 0f, dx + width, height.toFloat(), bgPaint)
    }

    private fun drawBackground(canvas: Canvas) {
        drawPageBackdrop(canvas, 0f)
    }

    private fun drawTransition(canvas: Canvas) {
        val from = pageIndex
        val to = animTarget
        val p = animProgress
        val forward = to > from
        val w = width.toFloat()

        when (turnMode) {
            ReaderPrefs.MODE_SLIDE -> {
                val dir = if (forward) 1f else -1f
                drawPage(canvas, from, -dir * p * w, 1f)
                drawPage(canvas, to, dir * (1f - p) * w, 1f)
            }
            ReaderPrefs.MODE_FADE -> {
                drawPage(canvas, from, 0f, 1f - p, opaque = false)
                drawPage(canvas, to, 0f, p, opaque = false)
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
            drawPage(canvas, if (progress >= 1f) animTarget else pageIndex, 0f, 1f)
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
        drawPage(canvas, pageIndex, 0f, 1f)
        canvas.restoreToCount(front)

        val back = canvas.save()
        canvas.clipPath(curl.paperBack)
        val reflected = canvas.save()
        canvas.concat(curl.reflection)
        drawPageBackdrop(canvas, 0f)
        drawPage(canvas, pageIndex, 0f, 0.13f, opaque = false)
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

    private fun drawPage(canvas: Canvas, index: Int, dx: Float, alpha: Float, opaque: Boolean = true) {
        val d = document ?: return
        val range = pages.getOrNull(index) ?: return
        val layout = layoutFor(index, range) ?: return
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()

        if (opaque) {
            drawPageBackdrop(canvas, dx)
        }

        bodyPaint.alpha = a
        canvas.save()
        canvas.translate(padH + dx, padTop)
        layout.draw(canvas)
        canvas.restore()

        footPaint.color = softColor
        footPaint.alpha = a
        val progress = (progressAt(index) * 100).toInt()
        val right = "$progress%"
        canvas.drawText(right, width - padH - footPaint.measureText(right) + dx, height - bottomInset - 12f * dp, footPaint)
        val pageLabel = "${index + 1}/${pages.size}"
        canvas.drawText(pageLabel, padH + dx, height - bottomInset - 12f * dp, footPaint)

        headPaint.alpha = 255
        footPaint.alpha = 255
        bodyPaint.alpha = 255
    }

    @SuppressLint("WrongConstant")
    private fun layoutFor(index: Int, range: IntRange): StaticLayout? {
        layoutCache.get(index)?.let { return it }
        if (range.first >= pageText.length) return null
        val content = pageText.subSequence(range.first, (range.last + 1).coerceAtMost(pageText.length))
        if (content.isEmpty()) return null
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, bodyPaint, (width - padH * 2).toInt())
            .setLineSpacing(0f, lineMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        layoutCache.put(index, layout)
        return layout
    }
}

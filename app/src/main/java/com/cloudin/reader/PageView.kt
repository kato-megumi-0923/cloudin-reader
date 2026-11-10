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
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * 分页阅读视图：排版、翻页动画、页眉页脚。
 * 分页和连续滚动共享排版，阅读位置按文字偏移记录。
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCenterTap: (() -> Unit)? = null
    var onPageChanged: ((pageIndex: Int, pageCount: Int, progress: Float) -> Unit)? = null
    var onChapterChanged: ((String) -> Unit)? = null
    var onImageTap: ((ReadingImage) -> Unit)? = null

    private var document: Document? = null
    private var pageText: CharSequence = ""

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var padH = 20f * dp
    private val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private var topInset = 0
    private val padTop get() = maxOf((if (landscape) 34f else 48f) * dp, topInset + 23f * dp)
    private var bottomInset = 0
    private val padBottom get() = 36f * dp + bottomInset

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("serif", Typeface.NORMAL)
        isSubpixelText = true
        textSize = 19f * resources.displayMetrics.scaledDensity
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
    private var anchorOffset = 0
    private var columns = 1
    private val gap get() = 24f * dp
    private val columnWidth get() = ((width - padH * 2 - (columns - 1) * gap) / columns).toInt()
    private val viewportHeight get() = (height - padTop - padBottom).coerceAtLeast(1f)
    private var withinBlock = 0f
    private var lastScrollY = 0f
    private var scrollDragging = false
    private var velocity: VelocityTracker? = null
    private var scrollFling: ValueAnimator? = null
    private var showFooter = true
    private var showChapter = true
    private var chromeVisible = false
    private var scrollLimit: Pair<Int, Float>? = null
    private val layoutCache = LruCache<Int, StaticLayout>(4)
    private var lineMultiplier = PageBreaker.LINE_MULTIPLIER

    private var theme = ReaderPrefs.THEME_PAPER
    private var turnMode = ReaderPrefs.MODE_CURL
    internal val usesCurl get() = turnMode == ReaderPrefs.MODE_CURL

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
        if (theme != newTheme) paperBitmap = null
        theme = newTheme
        softColor = ReaderPrefs.softInk(theme)
        bodyPaint.color = ReaderPrefs.ink(theme)
        bgPaint.color = ReaderPrefs.background(theme)
        layoutCache.evictAll()
        invalidate()
    }

    fun setTurnMode(mode: Int) {
        if (turnMode == mode) return
        val offset = currentOffset()
        stopMotion()
        turnMode = mode
        seekToOffset(offset)
        invalidate()
    }

    fun setDoubleColumns(enabled: Boolean) {
        val count = if (enabled) 2 else 1
        if (count == columns) return
        val offset = currentOffset()
        columns = count
        repaginate(offset)
    }

    fun setShowFooter(show: Boolean) {
        showFooter = show
        invalidate()
    }

    fun setShowChapter(show: Boolean) {
        showChapter = show
        invalidate()
    }

    fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        invalidate()
    }

    fun setFontFamily(family: String) {
        bodyPaint.typeface = Typeface.create(family, Typeface.NORMAL)
        repaginate()
    }

    fun setTopInset(inset: Int) {
        if (topInset == inset) return
        topInset = inset
        repaginate()
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

    private fun repaginate(offset: Int = currentOffset()) {
        stopMotion()
        pendingOffset = offset
        layoutCache.evictAll()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun setFontSize(sizeSp: Float) {
        bodyPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        repaginate()
    }

    fun setDocument(doc: Document, startOffset: Int) {
        stopMotion()
        document = doc
        pendingOffset = startOffset
        anchorOffset = startOffset
        pageIndex = 0
        layoutCache.evictAll()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun currentOffset(): Int = anchorOffset

    fun seekToOffset(offset: Int) {
        val d = document ?: return
        if (pages.isEmpty()) {
            pendingOffset = offset
            return
        }
        stopMotion()
        val target = offset.coerceIn(0, maxOf(0, d.text.length - 1))
        val idx = pages.indexOfLast { it.first <= target }.coerceAtLeast(0)
        pageIndex = idx / columns * columns
        anchorOffset = target
        withinBlock = 0f
        if (turnMode == ReaderPrefs.MODE_SCROLL) {
            val range = pages[idx]
            val layout = layoutFor(idx, range)
            if (layout != null) {
                val line = layout.getLineForOffset((target - range.first).coerceIn(0, layout.text.length))
                withinBlock = layout.getLineTop(line).toFloat()
            }
            clampScrollEnd()
        }
        pendingOffset = target
        invalidate()
        notifyChanged()
    }

    fun nextPage(y: Float = height / 2f) { if (turnMode == ReaderPrefs.MODE_SCROLL) advanceScroll(viewportHeight) else turn(1, y) }
    fun prevPage(y: Float = height / 2f) { if (turnMode == ReaderPrefs.MODE_SCROLL) advanceScroll(-viewportHeight) else turn(-1, y) }
    fun pageCount(): Int = (pages.size + columns - 1) / columns
    fun pageIndex(): Int = pageIndex / columns
    fun isAtEnd(): Boolean = pages.isNotEmpty() && if (turnMode == ReaderPrefs.MODE_SCROLL) {
        val (index, shift) = scrollEnd()
        pageIndex >= index && (pageIndex > index || withinBlock >= shift - 0.5f)
    } else pageIndex + columns >= pages.size

    private fun stopMotion() {
        animator?.removeAllListeners()
        animator?.cancel()
        scrollFling?.cancel()
        animating = false
        dragActive = false
        animProgress = 0f
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    private fun turn(delta: Int, y: Float = touchDownY) {
        if (document == null) return
        if (pages.isEmpty() || animating) return
        val target = pageIndex + delta * columns
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
            duration = 260L
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
        anchorOffset = pages.getOrNull(pageIndex)?.first ?: 0
        animating = false
        animProgress = 0f
        invalidate()
        notifyChanged()
        pendingOffset = currentOffset()
    }

    private fun notifyChanged() {
        val d = document ?: return
        if (pages.isEmpty()) return
        val progress = if (isAtEnd()) 1f else anchorOffset.toFloat() / d.text.length
        onPageChanged?.invoke(pageIndex(), pageCount(), progress)
        onChapterChanged?.invoke(d.chapterAt(anchorOffset))
    }

    private fun progressAt(index: Int): Float {
        val d = document ?: return 0f
        val page = pages.getOrNull(index) ?: return 0f
        return if (index + columns >= pages.size) 1f else page.first.toFloat() / d.text.length
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) paginate()
    }

    private fun paginate() {
        val d = document ?: return
        val w = columnWidth
        val h = viewportHeight.toInt()
        if (w <= 0 || h <= 0) return
        pageText = if (d.images.isEmpty()) d.text else SpannableString(d.text).apply {
            d.images.forEach { image ->
                setSpan(ReaderImageSpan(image.asset, w, h) { invalidate() },
                    image.offset, image.offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        pages = PageBreaker.breakPages(pageText, bodyPaint, w, h, lineMultiplier)
        scrollLimit = null
        layoutCache.evictAll()
        if (pages.isEmpty()) return
        seekToOffset(pendingOffset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (turnMode == ReaderPrefs.MODE_SCROLL) return onScrollTouch(event)
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
                    val vertical = turnMode == ReaderPrefs.MODE_VERTICAL
                    val canTurn = if (vertical) {
                        abs(dy) > touchSlop && abs(dy) > abs(dx)
                    } else if (turnMode == ReaderPrefs.MODE_CURL) {
                        abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.65f
                    } else {
                        abs(dx) > 16f * dp && abs(dy) < 90f * dp
                    }
                    if (canTurn) {
                        val dir = if ((if (vertical) dy else dx) < 0) 1 else -1
                        val target = pageIndex + dir * columns
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

    private fun handleTap(x: Float, y: Float) {
        if (animating) return
        val image = imageAt(x, y)
        if (image != null && onImageTap != null) {
            onImageTap?.invoke(image)
            return
        }
        when {
            x < width * 0.32f -> prevPage(y)
            x > width * 0.68f -> nextPage(y)
            else -> onCenterTap?.invoke()
        }
    }

    private fun imageAt(x: Float, y: Float): ReadingImage? {
        val images = document?.images ?: return null
        if (images.isEmpty() || y !in padTop..(padTop + viewportHeight)) return null
        val column = ((x - padH) / (columnWidth + gap)).toInt().coerceIn(0, columns - 1)
        val localX = x - padH - column * (columnWidth + gap)
        if (localX !in 0f..columnWidth.toFloat()) return null
        var index = pageIndex
        var localY = y - padTop + withinBlock
        while (turnMode == ReaderPrefs.MODE_SCROLL && index + columns < pages.size && localY >= blockHeight(index)) {
            localY -= blockHeight(index)
            index += columns
        }
        val range = pages.getOrNull(index + column) ?: return null
        val layout = layoutFor(index + column, range) ?: return null
        if (localY !in 0f..layout.height.toFloat()) return null
        val line = layout.getLineForVertical(localY.toInt())
        val start = range.first + layout.getLineStart(line)
        val end = range.first + layout.getLineEnd(line)
        val candidate = images.binarySearchBy(start) { it.offset }.let { if (it < 0) -it - 1 else it }
        val image = images.getOrNull(candidate)?.takeIf { it.offset < end } ?: return null
        val span = (pageText as? Spanned)?.getSpans(image.offset, image.offset + 1, ReaderImageSpan::class.java)?.firstOrNull()
        return image.asset.takeIf {
            span?.contains(localX - layout.getLineLeft(line), localY, layout.getLineBaseline(line)) == true
        }
    }

    private fun blockHeight(index: Int): Float {
        var block = 1
        for (column in 0 until columns) {
            val range = pages.getOrNull(index + column) ?: break
            block = maxOf(block, layoutFor(index + column, range)?.height ?: 1)
        }
        return block.toFloat()
    }

    private fun scrollEnd(): Pair<Int, Float> {
        scrollLimit?.let { return it }
        var index = (pages.lastIndex / columns) * columns
        var shift = blockHeight(index) - viewportHeight
        while (shift < 0 && index > 0) {
            index -= columns
            shift += blockHeight(index)
        }
        return (index to shift.coerceAtLeast(0f)).also { scrollLimit = it }
    }

    private fun clampScrollEnd() {
        val (endIndex, endShift) = scrollEnd()
        if (pageIndex > endIndex || (pageIndex == endIndex && withinBlock > endShift)) {
            pageIndex = endIndex
            withinBlock = endShift
        }
    }

    fun advanceScroll(distance: Float) {
        if (turnMode != ReaderPrefs.MODE_SCROLL || pages.isEmpty()) return
        withinBlock += distance
        while (withinBlock < 0f && pageIndex > 0) {
            pageIndex -= columns
            withinBlock += blockHeight(pageIndex)
        }
        withinBlock = withinBlock.coerceAtLeast(0f)
        while (pageIndex + columns < pages.size && withinBlock >= blockHeight(pageIndex)) {
            withinBlock -= blockHeight(pageIndex)
            pageIndex += columns
        }
        clampScrollEnd()
        val range = pages[pageIndex]
        val layout = layoutFor(pageIndex, range)
        val line = layout?.getLineForVertical(withinBlock.toInt()) ?: 0
        anchorOffset = range.first + (layout?.getLineStart(line) ?: 0)
        pendingOffset = anchorOffset
        invalidate()
        notifyChanged()
    }

    private fun onScrollTouch(event: MotionEvent): Boolean {
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
                    advanceScroll(lastScrollY - event.y)
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
                                    advanceScroll(value - previous)
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

    private fun turnDistance() = if (turnMode == ReaderPrefs.MODE_VERTICAL) height else width

    private fun dragTravel(x: Float, y: Float): Float {
        val distance = if (turnMode == ReaderPrefs.MODE_VERTICAL) y - touchDownY else x - touchDownX
        return if (animTarget > pageIndex) -distance else distance
    }

    private fun updateDrag(x: Float, y: Float) {
        if (turnMode == ReaderPrefs.MODE_CURL) {
            curlTipX = x.coerceIn(0f, width.toFloat())
            curlTipY = y.coerceIn(0f, height.toFloat())
        } else {
            animProgress = (dragTravel(x, y) / turnDistance().coerceAtLeast(1)).coerceIn(0f, 1f)
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
        if (turnMode == ReaderPrefs.MODE_SCROLL) {
            val save = canvas.save()
            canvas.clipRect(0f, padTop, width.toFloat(), padTop + viewportHeight)
            var top = -withinBlock
            var index = pageIndex
            while (index < pages.size && top < viewportHeight) {
                drawTextBlock(canvas, index, top)
                top += blockHeight(index)
                index += columns
            }
            canvas.restoreToCount(save)
            drawChapter(canvas, anchorOffset, 255)
            drawFooter(canvas, pageIndex, 255)
            return
        }
        if (!animating) {
            drawPage(canvas, pageIndex, 0f, 1f)
        } else {
            drawTransition(canvas)
        }
    }

    private var paperBitmap: Bitmap? = null
    private val paperBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)



    private fun drawPageBackdrop(canvas: Canvas) {
        val texture = ReaderPrefs.palette(theme).texture
        if (texture != 0) {
            val bmp = paperBitmap ?: runCatching {
                BitmapFactory.decodeResource(resources, texture)
            }.getOrNull()?.also { paperBitmap = it }
            if (bmp != null) {
                paperBitmapPaint.alpha = 255
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paperBitmapPaint)
                return
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun drawBackground(canvas: Canvas) {
        drawPageBackdrop(canvas)
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
        drawPageBackdrop(canvas)
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

    private fun drawPage(canvas: Canvas, index: Int, dx: Float, alpha: Float, opaque: Boolean = true, dy: Float = 0f) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val save = canvas.save()
        canvas.translate(dx, dy)
        if (opaque) drawPageBackdrop(canvas)
        bodyPaint.alpha = a
        drawTextBlock(canvas, index, 0f)
        drawChapter(canvas, pages.getOrNull(index)?.first ?: 0, a)
        drawFooter(canvas, index, a)
        bodyPaint.alpha = 255
        canvas.restoreToCount(save)
    }

    private fun drawTextBlock(canvas: Canvas, index: Int, y: Float) {
        for (column in 0 until columns) {
            val range = pages.getOrNull(index + column) ?: break
            val layout = layoutFor(index + column, range) ?: continue
            val save = canvas.save()
            canvas.translate(padH + column * (columnWidth + gap), padTop + y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun drawChapter(canvas: Canvas, offset: Int, alpha: Int) {
        if (!showChapter || chromeVisible) return
        val title = document?.chapterAt(offset).orEmpty()
        if (title.isEmpty()) return
        footPaint.color = softColor
        footPaint.alpha = alpha
        val count = footPaint.breakText(title, true, (width - padH * 2).coerceAtLeast(1f), null)
        canvas.drawText(title, 0, count, padH, padTop - 8f * dp, footPaint)
        footPaint.alpha = 255
    }

    private fun drawFooter(canvas: Canvas, index: Int, a: Int) {
        if (!showFooter) return
        footPaint.color = softColor
        footPaint.alpha = a
        val progress = if (turnMode == ReaderPrefs.MODE_SCROLL) {
            if (isAtEnd()) 100 else (100f * anchorOffset / (document?.text?.length ?: 1).coerceAtLeast(1)).toInt()
        } else (progressAt(index) * 100).toInt()
        val right = "$progress%"
        canvas.drawText(right, width - padH - footPaint.measureText(right), height - bottomInset - 12f * dp, footPaint)
        val pageLabel = "${index / columns + 1}/${pageCount()}"
        canvas.drawText(pageLabel, padH, height - bottomInset - 12f * dp, footPaint)

        footPaint.alpha = 255
    }

    @SuppressLint("WrongConstant")
    private fun layoutFor(index: Int, range: IntRange): StaticLayout? {
        layoutCache.get(index)?.let { return it }
        if (range.first >= pageText.length) return null
        val content = pageText.subSequence(range.first, (range.last + 1).coerceAtMost(pageText.length))
        if (content.isEmpty()) return null
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, bodyPaint, columnWidth)
            .setLineSpacing(0f, lineMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        layoutCache.put(index, layout)
        return layout
    }
}

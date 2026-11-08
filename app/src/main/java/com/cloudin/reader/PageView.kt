package com.cloudin.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
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

    private val dp = resources.displayMetrics.density
    private var padH = 20f * dp
    private val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val padTop = (if (landscape) 18f else 48f) * dp
    private val padBottom = 0f

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
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = ReaderPrefs.background(ReaderPrefs.THEME_PAPER) }
    private val camera = Camera()
    private val matrix = Matrix()
    private val shadePaint = Paint()

    private var pages: List<IntRange> = emptyList()
    private var pageIndex = 0
    private var pendingOffset = 0
    private val layoutCache = LruCache<Int, StaticLayout>(4)
    private var lineMultiplier = PageBreaker.LINE_MULTIPLIER

    private var theme = ReaderPrefs.THEME_PAPER
    private var turnMode = ReaderPrefs.MODE_CURL

    private var bgColor = ReaderPrefs.background(theme)
    private var inkColor = ReaderPrefs.ink(theme)
    private var softColor = ReaderPrefs.softInk(theme)

    private var animating = false
    private var animProgress = 0f
    private var animTarget = 0
    private var animator: ValueAnimator? = null

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var dragActive = false

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

    fun nextPage() = turn(1)
    fun prevPage() = turn(-1)
    fun pageCount(): Int = pages.size
    fun pageIndex(): Int = pageIndex

    private fun turn(delta: Int) {
        val d = document ?: return
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
        val progress = if (d.text.isEmpty()) 0f else p.first.toFloat() / d.text.length
        onPageChanged?.invoke(pageIndex, pages.size, progress)
        onChapterChanged?.invoke(d.chapterAt(p.first))
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
        pages = PageBreaker.breakPages(d.text, bodyPaint, w, h, lineMultiplier)
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
                touchDownTime = System.currentTimeMillis()
                dragActive = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragActive && pages.isNotEmpty() && !animating) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (abs(dx) > 16f * dp && abs(dy) < 90f * dp) {
                        val dir = if (dx < 0) 1 else -1
                        val target = pageIndex + dir
                        if (target in pages.indices) {
                            dragActive = true
                            animTarget = target
                            animating = true
                            animProgress = 0f
                        }
                    }
                }
                if (dragActive) {
                    animProgress = (abs(event.x - touchDownX) / width.toFloat()).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragActive) {
                    dragActive = false
                    val dt2 = System.currentTimeMillis() - touchDownTime
                    val dist = abs(event.x - touchDownX)
                    // 快速滑动（甩动）即使距离不够也完成翻页
                    val flung = dist > 56f * dp && dt2 < 280
                    settleDrag(animProgress > 0.32f || flung)
                    return true
                }
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val dt = System.currentTimeMillis() - touchDownTime
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

    private fun settleDrag(commit: Boolean) {
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
            else -> drawCurl(canvas, forward, p)
        }
    }

    /**
     * 仿真翻页：参照起点阅读——页面从右侧掀起，折线略带弧度，
     * 折边内侧宽而柔和的阴影，下一页从右侧透出。
     */
    private fun drawCurl(canvas: Canvas, forward: Boolean, p: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val from = pageIndex
        val to = animTarget
        val foldX = if (forward) w * (1f - p) else w * p
        val bulge = 18f * dp * (if (forward) (1f - p) else p)

        if (forward) {
            drawPage(canvas, to, 0f, 1f)
            drawEdgeShadow(canvas, foldX, 70f * dp, (70 * (1f - p)).toInt(), toRight = true)

            val path = android.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(foldX, 0f)
                quadTo(foldX + bulge, h / 2f, foldX, h)
                lineTo(0f, h)
                close()
            }
            val save = canvas.save()
            canvas.clipPath(path)
            drawPage(canvas, from, 0f, 1f)
            drawEdgeShadow(canvas, foldX, 150f * dp, (110 * (0.3f + p)).toInt().coerceAtMost(135), toRight = false)
            canvas.restoreToCount(save)
            drawFoldLine(canvas, foldX, h, bulge)
        } else {
            drawPage(canvas, to, 0f, 1f)
            drawEdgeShadow(canvas, foldX, 70f * dp, (70 * p).toInt(), toRight = false)

            val path = android.graphics.Path().apply {
                moveTo(w, 0f)
                lineTo(foldX, 0f)
                quadTo(foldX - bulge, h / 2f, foldX, h)
                lineTo(w, h)
                close()
            }
            val save = canvas.save()
            canvas.clipPath(path)
            drawPage(canvas, from, 0f, 1f)
            drawEdgeShadow(canvas, foldX, 150f * dp, (110 * (0.3f + (1f - p))).toInt().coerceAtMost(135), toRight = true)
            canvas.restoreToCount(save)
            drawFoldLine(canvas, foldX, h, -bulge)
        }
    }

    private fun drawEdgeShadow(canvas: Canvas, foldX: Float, widthPx: Float, alpha: Int, toRight: Boolean) {
        if (alpha <= 0 || widthPx <= 0f) return
        val a = alpha.coerceIn(0, 255)
        shadePaint.shader = if (toRight) {
            LinearGradient(
                foldX, 0f, foldX + widthPx, 0f,
                intArrayOf(a shl 24, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                foldX - widthPx, 0f, foldX, 0f,
                intArrayOf(0x00000000, a shl 24), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(
            if (toRight) foldX else foldX - widthPx, 0f,
            if (toRight) foldX + widthPx else foldX, height.toFloat(),
            shadePaint
        )
        shadePaint.shader = null
    }

    private fun drawFoldLine(canvas: Canvas, foldX: Float, h: Float, bulge: Float) {
        val path = android.graphics.Path().apply {
            moveTo(foldX, 0f)
            quadTo(foldX + bulge, h / 2f, foldX, h)
        }
        foldPaint.shader = null
        foldPaint.style = Paint.Style.STROKE
        foldPaint.strokeWidth = 1.3f * dp
        foldPaint.color = 0x4DFFFFFF
        canvas.drawPath(path, foldPaint)
        foldPaint.style = Paint.Style.FILL
    }

    private fun drawPage(canvas: Canvas, index: Int, dx: Float, alpha: Float, opaque: Boolean = true) {
        val d = document ?: return
        val range = pages.getOrNull(index) ?: return
        val layout = layoutFor(index, range, d) ?: return
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()

        if (opaque) {
            drawPageBackdrop(canvas, dx)
        }

        bodyPaint.alpha = a
        canvas.save()
        canvas.translate(padH + dx, padTop)
        layout.draw(canvas)
        canvas.restore()

        footPaint.alpha = a
        footPaint.color = softColor
        val progress = if (d.text.isEmpty()) 0 else range.first * 100 / d.text.length
        val right = "$progress%"
        canvas.drawText(right, width - padH - footPaint.measureText(right) + dx, height - 12f * dp, footPaint)
        val pageLabel = "${index + 1}/${pages.size}"
        canvas.drawText(pageLabel, padH + dx, height - 12f * dp, footPaint)

        headPaint.alpha = 255
        footPaint.alpha = 255
        bodyPaint.alpha = 255
    }

    private fun layoutFor(index: Int, range: IntRange, d: Document): StaticLayout? {
        layoutCache.get(index)?.let { return it }
        val text = d.text
        if (range.first >= text.length) return null
        val pageText = text.substring(range.first, (range.last + 1).coerceAtMost(text.length))
        if (pageText.isEmpty()) return null
        val layout = StaticLayout.Builder
            .obtain(pageText, 0, pageText.length, bodyPaint, (width - padH * 2).toInt())
            .setLineSpacing(0f, lineMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        layoutCache.put(index, layout)
        return layout
    }
}

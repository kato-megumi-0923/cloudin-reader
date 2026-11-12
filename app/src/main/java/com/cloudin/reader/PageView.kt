package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    private class Opening(val cache: PaginationCache?, val onReady: (Boolean) -> Unit)
    private var opening: Opening? = null
    private var layoutJob: Future<*>? = null
    private var layoutGeneration = 0

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
    private var fontFamily = "serif"
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * resources.displayMetrics.scaledDensity
    }
    private val bgPaint = Paint().apply { color = ReaderPrefs.background(ReaderPrefs.THEME_PAPER) }

    private var pageIndex = 0
    private var pendingOffset = 0
    private var anchorOffset = 0
    private val viewportHeight get() = (height - padTop - padBottom).coerceAtLeast(1f)
    private var showFooter = true
    private var showChapter = true
    private var chromeVisible = false

    private var theme = ReaderPrefs.THEME_PAPER
    private var softColor = ReaderPrefs.softInk(theme)
    private val paginator = Paginator(bodyPaint, 24f * dp, ::invalidate)
    private val turnAnimator = TurnAnimator(
        this, dp, touchSlop, { pageIndex }, ::targetPage, ::applyPage,
        ::handleTap, ::drawPage, ::drawPageBackdrop
    )
    private val scrollController = ScrollController(
        paginator, { viewportHeight }, touchSlop, ::advanceScroll, ::handleTap
    )
    private val pages get() = paginator.pages
    private val columns get() = paginator.columns
    private val turnMode get() = turnAnimator.mode
    internal val usesCurl get() = turnMode == ReaderPrefs.MODE_CURL

    init {
        isClickable = true
    }

    fun setTheme(newTheme: Int) {
        if (theme != newTheme) paperBitmap = null
        theme = newTheme
        softColor = ReaderPrefs.softInk(theme)
        bodyPaint.color = ReaderPrefs.ink(theme)
        bgPaint.color = ReaderPrefs.background(theme)
        paginator.clearLayoutCache()
        invalidate()
    }

    fun setTurnMode(mode: Int) {
        if (turnMode == mode) return
        val offset = currentOffset()
        stopMotion()
        turnAnimator.mode = mode
        seekToOffset(offset)
        invalidate()
    }

    /** 搜索态：只高亮当前这一处命中（offset/length），color 传 0 清除。 */
    fun setSearchHit(offset: Int, length: Int, color: Int) {
        paginator.setSearchHit(offset, length, color)
        invalidate()
    }

    fun setDoubleColumns(enabled: Boolean) {
        val requestedColumns = if (enabled) 2 else 1
        if (requestedColumns == columns) return
        val offset = currentOffset()
        paginator.columns = requestedColumns
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
        fontFamily = family
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
        paginator.lineMultiplier = 1.25f + percent.coerceIn(0, 100) / 100f * 0.8f
        repaginate()
    }

    private fun dpScale(v: Float): Float = v * dp

    private fun repaginate(offset: Int = currentOffset()) {
        stopMotion()
        pendingOffset = offset
        paginator.clearLayoutCache()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun setFontSize(sizeSp: Float) {
        bodyPaint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        repaginate()
    }

    fun setDocument(doc: Document, startOffset: Int) {
        setDocument(doc, startOffset, null)
    }

    internal fun openDocument(doc: Document, startOffset: Int, bookId: String, onReady: (Boolean) -> Unit) {
        val cache = if (doc.text.length >= BACKGROUND_LAYOUT_THRESHOLD) PaginationCache(context.cacheDir, bookId) else null
        setDocument(doc, startOffset, Opening(cache, onReady))
    }

    private fun setDocument(doc: Document, startOffset: Int, request: Opening?) {
        cancelLayoutJob()
        stopMotion()
        document = doc
        opening = request
        pendingOffset = startOffset
        anchorOffset = startOffset
        pageIndex = 0
        paginator.clear()
        if (width > 0 && height > 0) paginate()
        invalidate()
    }

    fun currentOffset(): Int = anchorOffset

    fun seekToOffset(offset: Int) {
        val d = document ?: return
        if (pages.isEmpty()) {
            pendingOffset = offset
            anchorOffset = offset
            return
        }
        stopMotion()
        val target = offset.coerceIn(0, maxOf(0, d.text.length - 1))
        val column = paginator.columnAtOffset(target)
        pageIndex = column / columns * columns
        anchorOffset = target
        if (turnMode == ReaderPrefs.MODE_SCROLL) {
            pageIndex = scrollController.seek(pageIndex, paginator.lineTopAt(column, target))
        }
        pendingOffset = target
        invalidate()
        notifyChanged()
    }

    fun nextPage(y: Float = height / 2f) { if (turnMode == ReaderPrefs.MODE_SCROLL) advanceScroll(viewportHeight) else turnAnimator.turn(1, y) }
    fun prevPage(y: Float = height / 2f) { if (turnMode == ReaderPrefs.MODE_SCROLL) advanceScroll(-viewportHeight) else turnAnimator.turn(-1, y) }
    fun pageCount(): Int = (pages.size + columns - 1) / columns
    fun pageIndex(): Int = pageIndex / columns
    fun isAtEnd(): Boolean = pages.isNotEmpty() && if (turnMode == ReaderPrefs.MODE_SCROLL) {
        scrollController.isAtEnd(pageIndex)
    } else pageIndex + columns >= pages.size

    private fun stopMotion() {
        turnAnimator.stop()
        scrollController.stop()
    }

    override fun onDetachedFromWindow() {
        cancelLayoutJob()
        stopMotion()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (opening != null && layoutJob == null && width > 0 && height > 0) paginate()
    }

    private fun targetPage(direction: Int): Int? {
        val target = pageIndex + direction * columns
        return target.takeIf { document != null && it in pages.indices }
    }

    private fun applyPage(index: Int) {
        pageIndex = index.coerceIn(0, maxOf(0, pages.size - 1))
        anchorOffset = pages.getOrNull(pageIndex)?.first ?: 0
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
        val size = paginator.sizeFor(width - padH * 2, viewportHeight.toInt()) ?: return
        val request = opening
        val multiplier = paginator.lineMultiplier
        val family = fontFamily
        val cache = request?.cache
        if (cache == null) {
            applyLayout(paginator.prepare(d, size, bodyPaint, multiplier, family))
            return
        }
        cancelLayoutJob()
        val generation = layoutGeneration
        val measurePaint = TextPaint(bodyPaint)
        layoutJob = layoutWorker.submit {
            val layout = try {
                paginator.prepare(d, size, measurePaint, multiplier, family, cache)
            } catch (_: InterruptedException) {
                return@submit
            } catch (error: Exception) {
                android.util.Log.e("PageView", "Failed to paginate book", error)
                null
            }
            main.post {
                if (generation == layoutGeneration) {
                    layoutJob = null
                    if (layout != null) applyLayout(layout) else finishOpening(false)
                }
            }
        }
    }

    private fun cancelLayoutJob() {
        layoutGeneration++
        layoutJob?.cancel(true)
        layoutJob = null
    }

    private fun applyLayout(layout: Paginator.PreparedLayout) {
        paginator.apply(layout)
        scrollController.resetLimit()
        if (pages.isNotEmpty()) seekToOffset(pendingOffset)
        finishOpening(pages.isNotEmpty())
    }

    private fun finishOpening(ready: Boolean) {
        val request = opening ?: return
        opening = null
        request.onReady(ready)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (turnMode == ReaderPrefs.MODE_SCROLL) scrollController.onTouchEvent(event)
        else turnAnimator.onTouchEvent(event)

    private fun handleTap(x: Float, y: Float) {
        if (turnAnimator.animating) return
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
        val d = document ?: return null
        if (d.images.isEmpty() || y !in padTop..(padTop + viewportHeight)) return null
        val localY = y - padTop
        return if (turnMode == ReaderPrefs.MODE_SCROLL) {
            val (index, blockY) = scrollController.positionAt(pageIndex, localY)
            paginator.imageAt(d, index, x - padH, blockY)
        } else {
            paginator.imageAt(d, pageIndex, x - padH, localY)
        }
    }

    fun advanceScroll(distance: Float) {
        if (turnMode != ReaderPrefs.MODE_SCROLL || pages.isEmpty()) return
        val previousPage = pageIndex
        val previousShift = scrollController.withinBlock
        val previousOffset = anchorOffset
        val wasAtEnd = isAtEnd()
        pageIndex = scrollController.advance(pageIndex, distance)
        if (pageIndex == previousPage && scrollController.withinBlock == previousShift) return
        anchorOffset = paginator.offsetAt(pageIndex, scrollController.withinBlock)
        pendingOffset = anchorOffset
        invalidate()
        if (pageIndex != previousPage || anchorOffset != previousOffset || wasAtEnd != isAtEnd()) {
            notifyChanged()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (turnAnimator.animating && usesCurl && pages.isNotEmpty() && document != null) {
            turnAnimator.draw(canvas)
            return
        }
        drawPageBackdrop(canvas)
        if (pages.isEmpty() || document == null) return
        if (turnMode == ReaderPrefs.MODE_SCROLL) {
            val save = canvas.save()
            canvas.clipRect(0f, padTop, width.toFloat(), padTop + viewportHeight)
            scrollController.forEachVisibleBlock(pageIndex) { index, top ->
                paginator.drawBlock(canvas, index, padH, padTop + top)
            }
            canvas.restoreToCount(save)
            drawChapter(canvas, anchorOffset, 255)
            drawFooter(canvas, pageIndex, 255)
        } else if (turnAnimator.animating) {
            turnAnimator.draw(canvas)
        } else {
            drawPage(canvas, pageIndex, 0f, 1f)
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

    private fun drawPage(canvas: Canvas, index: Int, dx: Float, alpha: Float, opaque: Boolean = true, dy: Float = 0f) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val save = canvas.save()
        canvas.translate(dx, dy)
        if (opaque) drawPageBackdrop(canvas)
        bodyPaint.alpha = a
        paginator.drawBlock(canvas, index, padH, padTop)
        drawChapter(canvas, pages.getOrNull(index)?.first ?: 0, a)
        drawFooter(canvas, index, a)
        bodyPaint.alpha = 255
        canvas.restoreToCount(save)
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
    private companion object {
        const val BACKGROUND_LAYOUT_THRESHOLD = 100_000
        val layoutWorker = Executors.newSingleThreadExecutor { Thread(it, "ReaderPagination") }
        val main = Handler(Looper.getMainLooper())
    }
}

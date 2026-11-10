package com.cloudin.reader

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache

/** Shared text and image layout for paged and continuous reading. */
internal class Paginator(
    private val paint: TextPaint,
    private val columnGap: Float,
    private val invalidate: () -> Unit
) {
    data class Size(val width: Int, val height: Int)
    class PreparedLayout(val text: CharSequence, val pages: List<IntRange>, val size: Size)

    var columns = 1
    var lineMultiplier = PageBreaker.LINE_MULTIPLIER
    var pages: List<IntRange> = emptyList()
        private set

    private var pageText: CharSequence = ""
    private var columnWidth = 0
    private val layoutCache = LruCache<Int, StaticLayout>(4)

    fun sizeFor(contentWidth: Float, height: Int): Size? {
        val width = ((contentWidth - (columns - 1) * columnGap) / columns).toInt()
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    fun prepare(
        document: Document,
        size: Size,
        measurePaint: TextPaint,
        multiplier: Float,
        fontFamily: String,
        cache: PaginationCache? = null
    ): PreparedLayout {
        val text = if (document.images.isEmpty()) document.text else SpannableString(document.text).apply {
            document.images.forEach { image ->
                setSpan(
                    ReaderImageSpan(image.asset, size.width, size.height, invalidate),
                    image.offset, image.offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val layoutKey = cache?.key(document, listOf(
            size.width, size.height, multiplier, fontFamily,
            measurePaint.textSize, measurePaint.textLocales.toLanguageTags()
        ).joinToString("\n"))
        val ranges = layoutKey?.let { cache.read(it, text.length) }
            ?: PageBreaker.breakPages(text, measurePaint, size.width, size.height, multiplier).also {
                if (layoutKey != null) cache.write(layoutKey, it)
            }
        return PreparedLayout(text, ranges, size)
    }

    fun apply(layout: PreparedLayout) {
        columnWidth = layout.size.width
        pageText = layout.text
        pages = layout.pages
        clearLayoutCache()
    }

    fun clear() {
        pageText = ""
        pages = emptyList()
        clearLayoutCache()
    }

    fun clearLayoutCache() = layoutCache.evictAll()

    fun columnAtOffset(offset: Int): Int {
        val match = pages.binarySearchBy(offset) { it.first }
        return if (match >= 0) match else (-match - 2).coerceAtLeast(0)
    }

    fun lineTopAt(index: Int, offset: Int): Float {
        val range = pages[index]
        val layout = layoutFor(index, range) ?: return 0f
        val line = layout.getLineForOffset((offset - range.first).coerceIn(0, layout.text.length))
        return layout.getLineTop(line).toFloat()
    }

    fun offsetAt(index: Int, y: Float): Int {
        val range = pages[index]
        val layout = layoutFor(index, range) ?: return range.first
        val line = layout.getLineForVertical(y.toInt())
        return range.first + layout.getLineStart(line)
    }

    fun blockHeight(index: Int): Float {
        var block = 1
        for (column in 0 until columns) {
            val range = pages.getOrNull(index + column) ?: break
            block = maxOf(block, layoutFor(index + column, range)?.height ?: 1)
        }
        return block.toFloat()
    }

    fun drawBlock(canvas: Canvas, index: Int, left: Float, top: Float) {
        for (column in 0 until columns) {
            val range = pages.getOrNull(index + column) ?: break
            val layout = layoutFor(index + column, range) ?: continue
            val save = canvas.save()
            canvas.translate(left + column * (columnWidth + columnGap), top)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    fun imageAt(document: Document, index: Int, x: Float, localY: Float): ReadingImage? {
        val images = document.images
        if (images.isEmpty()) return null
        val column = (x / (columnWidth + columnGap)).toInt().coerceIn(0, columns - 1)
        val localX = x - column * (columnWidth + columnGap)
        if (localX !in 0f..columnWidth.toFloat()) return null
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

    @SuppressLint("WrongConstant")
    private fun layoutFor(index: Int, range: IntRange): StaticLayout? {
        layoutCache.get(index)?.let { return it }
        if (range.first >= pageText.length) return null
        val content = pageText.subSequence(range.first, (range.last + 1).coerceAtMost(pageText.length))
        if (content.isEmpty()) return null
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, paint, columnWidth)
            .setLineSpacing(0f, lineMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        layoutCache.put(index, layout)
        return layout
    }
}

package com.cloudin.reader

import android.annotation.SuppressLint

import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.min

/** 把长文本按屏幕尺寸切成页。返回每页的字符区间。 */
object PageBreaker {

    const val LINE_MULTIPLIER = 1.62f
    private const val CHUNK = 6000

    @SuppressLint("WrongConstant")
    fun breakPages(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        height: Int,
        multiplier: Float = LINE_MULTIPLIER,
        chapterStarts: IntArray = IntArray(0)
    ): List<IntRange> {
        val pages = ArrayList<IntRange>(text.length / 400 + 1)
        if (width <= 0 || height <= 0) return pages
        var start = 0
        var lookahead = 1024
        var breakIndex = 0
        while (start < text.length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            // 跳过页首空白
            while (start < text.length && text[start] == '\n') start++
            if (start >= text.length) break
            // 本章起点已经在页首，跳到下一个章节起点
            while (breakIndex < chapterStarts.size && chapterStarts[breakIndex] <= start) breakIndex++
            val chapterStart = if (breakIndex < chapterStarts.size) chapterStarts[breakIndex] else Int.MAX_VALUE
            val limit = min(text.length, start + CHUNK)
            var probe = min(limit, start + lookahead)
            var chars: Int
            while (true) {
                // Keep paragraphs whole so high-quality line breaking sees the same text.
                val newline = TextUtils.indexOf(text, '\n', probe, limit)
                val end = if (newline < 0) limit else newline + 1
                val chunk = text.subSequence(start, end)
                val layout = StaticLayout.Builder
                    .obtain(chunk, 0, chunk.length, paint, width)
                    .setLineSpacing(0f, multiplier)
                    .setIncludePad(false)
                    .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                    .build()

                var lastLine = 0
                for (i in 0 until layout.lineCount) {
                    if (layout.getLineBottom(i) <= height) lastLine = i else break
                }
                chars = layout.getLineEnd(lastLine)
                // A final empty line after a newline is not proof that the page is full.
                if (end == limit || chars < chunk.length) break
                probe = min(limit, end + (end - start) / 2)
            }
            val charEnd = start + chars.coerceAtLeast(1)
            // 章节起点落在本页内部时，强制在此断页，让新章节从新的一页开始
            val pageEnd = if (chapterStart in (start + 1) until charEnd) chapterStart else charEnd
            pages.add(start until pageEnd)
            lookahead = ((pageEnd - start) + (pageEnd - start) / 2 + 128).coerceIn(512, CHUNK)
            start = pageEnd
        }
        return pages
    }

    fun lineHeight(paint: Paint): Int = (paint.textSize * LINE_MULTIPLIER).toInt()
}

package com.cloudin.reader

import android.annotation.SuppressLint

import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

/** 把长文本按屏幕尺寸切成页。返回每页的字符区间。 */
object PageBreaker {

    const val LINE_MULTIPLIER = 1.62f
    private const val CHUNK = 6000

    @SuppressLint("WrongConstant")
    fun breakPages(text: CharSequence, paint: TextPaint, width: Int, height: Int, multiplier: Float = LINE_MULTIPLIER): List<IntRange> {
        val pages = ArrayList<IntRange>(text.length / 400 + 1)
        if (width <= 0 || height <= 0) return pages
        var start = 0
        while (start < text.length) {
            // 跳过页首空白
            while (start < text.length && text[start] == '\n') start++
            if (start >= text.length) break
            val end = min(text.length, start + CHUNK)
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
            var charEnd = start + layout.getLineEnd(lastLine)
            if (charEnd <= start) charEnd = start + 1
            pages.add(start until charEnd)
            start = charEnd
        }
        return pages
    }

    fun lineHeight(paint: Paint): Int = (paint.textSize * LINE_MULTIPLIER).toInt()
}

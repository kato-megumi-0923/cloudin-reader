package com.cloudin.reader

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Html
import android.text.style.ImageSpan
import java.io.File

data class ChapterMark(val title: String, val offset: Int)
data class ReadingImage(val path: String, val width: Int, val height: Int)
data class InlineImage(val offset: Int, val asset: ReadingImage)

data class Document(
    val title: String,
    val text: String,
    val chapters: List<ChapterMark>,
    val images: List<InlineImage> = emptyList()
) {
    fun chapterAt(offset: Int): String {
        var title = ""
        for (c in chapters) {
            if (c.offset <= offset) title = c.title else break
        }
        return title
    }
}

object DocumentLoader {

    fun load(book: Book): Document? {
        return when (book.format) {
            "txt" -> loadTxt(book)
            "epub" -> loadEpub(book)
            else -> null
        }
    }

    private fun loadTxt(book: Book): Document? {
        val file = File(book.path)
        if (!file.exists()) return null
        val text = runCatching { TextCodec.decode(file) }.getOrNull() ?: return null
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return null

        val builder = StringBuilder(normalized.length + normalized.length / 8)
        val marks = mutableListOf<ChapterMark>()
        for (raw in normalized.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                builder.append('\n')
                continue
            }
            val isHeading = isChapterHeading(line)
            if (isHeading) {
                marks.add(ChapterMark(line, builder.length))
                builder.append(line).append('\n')
            } else {
                builder.append("　　").append(line).append('\n')
            }
        }
        return Document(book.title, builder.toString().trim(), marks)
    }

    private val headingRegex = Regex(
        "^(第[0-9零〇一二三四五六七八九十百千两]{1,8}[章回节卷篇]|序章|序言|楔子|引子|尾声|后记|番外)"
    )

    private fun isChapterHeading(line: String): Boolean =
        line.length <= 40 && headingRegex.containsMatchIn(line)

    private fun loadEpub(book: Book): Document? {
        val dir = File(book.path).canonicalFile
        val info = EpubParser.readInfo(dir) ?: return null
        val builder = StringBuilder()
        val marks = mutableListOf<ChapterMark>()
        val images = mutableListOf<InlineImage>()
        val knownImages = mutableMapOf<String, ReadingImage?>()
        for (chapter in info.chapters) {
            val html = runCatching { chapter.file.readText() }.getOrNull() ?: continue
            val body = htmlBody(html)
            val spanned = runCatching {
                Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY, { source ->
                    val asset = resolveImage(dir, chapter.file, source, knownImages)
                    asset?.let(::ImageReference)
                }, null)
            }.getOrNull() ?: continue
            val text = spanned.toString()
            val chapterText = StringBuilder()
            fun appendText(start: Int, end: Int) {
                val cleaned = text.substring(start, end).replace('\u00a0', ' ').replace("\ufffc", "")
                    .replace(repeatedBreaks, "\n\n").trim()
                if (cleaned.isEmpty()) return
                if (chapterText.isNotEmpty()) chapterText.append('\n')
                chapterText.append(indent(cleaned))
            }
            var position = 0
            for (span in spanned.getSpans(0, spanned.length, ImageSpan::class.java).sortedBy { spanned.getSpanStart(it) }) {
                val start = spanned.getSpanStart(span)
                if (start < position) continue
                appendText(position, start)
                (span.drawable as? ImageReference)?.let { reference ->
                    if (chapterText.isNotEmpty()) chapterText.append('\n')
                    images.add(InlineImage(builder.length + chapterText.length, reference.asset))
                    chapterText.append('\ufffc')
                }
                position = spanned.getSpanEnd(span)
            }
            appendText(position, text.length)
            if (chapterText.isBlank()) continue
            marks.add(ChapterMark(extractTitle(html) ?: chapter.title, builder.length))
            builder.append(chapterText).append("\n\n")
        }
        if (builder.isBlank()) return null
        return Document(book.title, builder.toString().trimEnd(), marks, images)
    }

    private class ImageReference(val asset: ReadingImage) : ColorDrawable(Color.TRANSPARENT) {
        init { setBounds(0, 0, 1, 1) }
    }

    private fun resolveImage(
        root: File,
        chapter: File,
        source: String?,
        known: MutableMap<String, ReadingImage?>
    ): ReadingImage? {
        val uri = Uri.parse(source?.trim()?.takeIf { it.isNotEmpty() } ?: return null)
        if (uri.scheme != null || uri.authority != null) return null
        val path = uri.path ?: return null
        val file = runCatching {
            if (path.startsWith('/')) File(root, path.trimStart('/')).canonicalFile
            else File(chapter.parentFile, path).canonicalFile
        }.getOrNull() ?: return null
        val key = file.path
        if (!key.startsWith(root.path + File.separator)) return null
        if (known.containsKey(key)) return known[key]
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key, options)
        val asset = if (options.outWidth > 0 && options.outHeight > 0) {
            ReadingImage(key, options.outWidth, options.outHeight)
        } else null
        known[key] = asset
        return asset
    }

    private fun extractTitle(html: String): String? {
        for (tag in listOf("h1", "h2", "h3", "title")) {
            val match = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html) ?: continue
            val raw = match.groupValues.getOrNull(1) ?: continue
            val text = runCatching { Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString() }.getOrNull() ?: continue
            val clean = text.trim().replace(Regex("\\s+"), " ")
            if (clean.isNotBlank()) return clean
        }
        return null
    }

    private val repeatedBreaks = Regex("\\n{3,}")
    private val htmlOptions = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val bodyTag = Regex("<body[^>]*>(.*?)</body>", htmlOptions)
    private val ignoredTags = Regex("<(script|style)\\b[^>]*>.*?</\\1>", htmlOptions)
    private val svgImage = Regex("<(?:svg:)?image\\b([^>]*)>", htmlOptions)
    private val imageHref = Regex("(?:^|\\s)(?:xlink:)?href\\s*=\\s*([\"'])(.*?)\\1", htmlOptions)

    private fun htmlBody(html: String): String {
        val body = bodyTag.find(html)?.groupValues?.get(1) ?: html
        return svgImage.replace(body.replace(ignoredTags, "")) { tag ->
            val href = imageHref.find(tag.groupValues[1])
            if (href == null) "" else "<img src=${href.groupValues[1]}${href.groupValues[2]}${href.groupValues[1]}>"
        }
    }

    /** 段落首行缩进两个全角空格；章节标题和短行不缩进。 */
    private fun indent(text: String): String {
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> ""
                isChapterHeading(t) || t.length <= 12 -> t
                else -> "　　" + t
            }
        }
    }
}

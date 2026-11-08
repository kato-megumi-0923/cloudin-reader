package com.cloudin.reader

import android.text.Html
import java.io.File
import java.nio.charset.Charset

data class ChapterMark(val title: String, val offset: Int)

data class Document(
    val title: String,
    val text: String,
    val chapters: List<ChapterMark>
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
        val dir = File(book.path)
        val info = EpubParser.readInfo(dir) ?: return null
        val builder = StringBuilder()
        val marks = mutableListOf<ChapterMark>()
        info.chapters.forEachIndexed { index, chapter ->
            val html = runCatching { chapter.file.readText() }.getOrNull() ?: return@forEachIndexed
            val plain = htmlToText(html)
            if (plain.isBlank()) return@forEachIndexed
            val title = extractTitle(html) ?: chapter.title
            marks.add(ChapterMark(title, builder.length))
            builder.append(indent(plain)).append("\n\n")
        }
        if (builder.isBlank()) return null
        return Document(book.title, builder.toString().trim(), marks)
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

    private fun htmlToText(html: String): String {
        val body = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: html
        val cleaned = body
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        val spanned = runCatching { Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY) }.getOrNull()
            ?: return ""
        return spanned.toString()
            .replace('\u00a0', ' ')
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
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

package com.cloudin.reader

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** title 为 null 表示目录里没给名字，由调用方决定兜底文案。 */
data class EpubChapter(val title: String?, val file: File)

data class EpubInfo(
    val title: String,
    val author: String,
    val coverPath: String?,
    val chapters: List<EpubChapter>
)

object EpubParser {

    /** Unzip the epub and read its metadata. Returns null when the file is not a valid EPUB. */
    fun parse(epub: File, dir: File, coversDir: File, bookId: String, maxExpandedBytes: Long): EpubInfo? {
        return try {
            dir.mkdirs()
            unzip(epub, dir, maxExpandedBytes)
            readInfo(dir, coversDir, bookId)
        } catch (e: Exception) {
            null
        }
    }

    fun readInfo(dir: File, coversDir: File? = null, bookId: String? = null): EpubInfo? {
        val container = File(dir, "META-INF/container.xml")
        if (!container.exists()) return null
        val rootPath = parseContainer(container) ?: return null
        val opf = resolveInside(dir, dir, rootPath) ?: return null
        if (!opf.exists()) return null
        val doc = readXml(opf)
        val opfDir = opf.parentFile ?: dir

        fun firstText(vararg tagNames: String): String {
            for (t in tagNames) {
                val nodes = doc.getElementsByTagName(t)
                if (nodes.length > 0) {
                    val txt = nodes.item(0).textContent?.trim().orEmpty()
                    if (txt.isNotEmpty()) return txt
                }
            }
            return ""
        }

        val title = firstText("dc:title", "title")
        val author = firstText("dc:creator", "creator")

        // manifest
        val items = mutableMapOf<String, ManifestItem>()
        val itemNodes = doc.getElementsByTagName("item")
        for (i in 0 until itemNodes.length) {
            val e = itemNodes.item(i) as? Element ?: continue
            val id = e.getAttribute("id")
            val href = e.getAttribute("href")
            val mediaType = e.getAttribute("media-type")
            val props = e.getAttribute("properties")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                items[id] = ManifestItem(id, href, mediaType, props)
            }
        }

        // cover
        var coverHref: String? = null
        items.values.firstOrNull { it.properties?.contains("cover-image") == true }?.let {
            coverHref = it.href
        }
        if (coverHref == null) {
            val metas = doc.getElementsByTagName("meta")
            for (i in 0 until metas.length) {
                val e = metas.item(i) as? Element ?: continue
                if (e.getAttribute("name") == "cover") {
                    val cid = e.getAttribute("content")
                    coverHref = items[cid]?.href
                }
            }
        }

        // spine -> chapters
        // 章节名优先取目录（toc.ncx / nav.xhtml），因为不少电子书正文里没有 h1~h3，
        // 连 <title> 都是空的，只能靠目录拿到「第一章 …」这样的真名字。
        val tocTitles = readTocTitles(dir, opfDir, doc, items)
        val chapters = mutableListOf<EpubChapter>()
        val spineNodes = doc.getElementsByTagName("itemref")
        for (i in 0 until spineNodes.length) {
            val e = spineNodes.item(i) as? Element ?: continue
            val idref = e.getAttribute("idref")
            val item = items[idref] ?: continue
            if (item.mediaType != null && !item.mediaType.contains("html")) continue
            val f = resolveInside(dir, opfDir, item.href) ?: continue
            if (!f.isFile) continue
            chapters.add(EpubChapter(tocTitles[f.absolutePath], f))
        }
        if (chapters.isEmpty()) return null

        var coverPath: String? = null
        if (coverHref != null && coversDir != null && bookId != null) {
            val src = resolveInside(dir, opfDir, coverHref)
            if (src?.isFile == true) {
                val ext = src.extension.ifEmpty { "jpg" }
                val dst = File(coversDir, "$bookId.$ext")
                runCatching { src.copyTo(dst, overwrite = true); coverPath = dst.absolutePath }
            }
        }

        return EpubInfo(title, author, coverPath, chapters)
    }

    fun chapters(context: android.content.Context, book: Book): List<EpubChapter> {
        val dir = File(book.path)
        return readInfo(dir)?.chapters ?: emptyList()
    }

    /**
     * 读出目录里的章节名：文件绝对路径 → 标题。
     * 先找 EPUB2 的 toc.ncx，没有再用 EPUB3 的 nav.xhtml。
     */
    private fun readTocTitles(
        root: File,
        opfDir: File,
        doc: org.w3c.dom.Document,
        items: Map<String, ManifestItem>
    ): Map<String, String> {
        val titles = mutableMapOf<String, String>()

        val ncxItem = items.values.firstOrNull { it.mediaType?.contains("dtbncx") == true }
            ?: items.values.firstOrNull { it.href.endsWith(".ncx", ignoreCase = true) }
        val ncxFile = ncxItem?.let { resolveInside(root, opfDir, it.href) }
        if (ncxFile?.isFile == true) {
            runCatching {
                val ncx = readXml(ncxFile)
                val points = ncx.getElementsByTagName("navPoint")
                for (i in 0 until points.length) {
                    val point = points.item(i) as? Element ?: continue
                    val label = childText(point, "navLabel", "text") ?: continue
                    val src = childAttr(point, "content", "src") ?: continue
                    val file = resolveInside(root, opfDir, src.substringBefore('#')) ?: continue
                    titles.putIfAbsent(file.absolutePath, label)
                }
            }
        }
        if (titles.isNotEmpty()) return titles

        val navItem = items.values.firstOrNull { it.properties?.contains("nav") == true }
        val navFile = navItem?.let { resolveInside(root, opfDir, it.href) }
        if (navFile?.isFile == true) {
            runCatching {
                val html = navFile.readText()
                val tocBlock = Regex(
                    "<nav[^>]*epub:type\\s*=\\s*[\"']toc[\"'][^>]*>(.*?)</nav>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(html)?.groupValues?.get(1) ?: return@runCatching
                Regex(
                    "<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).findAll(tocBlock).forEach { match ->
                    val label = match.groupValues[2].replace(Regex("<[^>]+>"), "")
                        .trim().replace(Regex("\\s+"), " ")
                    if (label.isEmpty()) return@forEach
                    val file = resolveInside(root, opfDir, match.groupValues[1].substringBefore('#'))
                        ?: return@forEach
                    titles.putIfAbsent(file.absolutePath, label)
                }
            }
        }
        return titles
    }

    private fun childElement(parent: Element, tag: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName.equals(tag, ignoreCase = true)) return node
        }
        return null
    }

    private fun childText(parent: Element, vararg path: String): String? {
        var current: Element = parent
        for (tag in path) current = childElement(current, tag) ?: return null
        return current.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun childAttr(parent: Element, tag: String, attribute: String): String? =
        childElement(parent, tag)?.getAttribute(attribute)?.takeIf { it.isNotBlank() }

    private fun parseContainer(container: File): String? {
        return try {
            val doc = readXml(container)
            val nodes = doc.getElementsByTagName("rootfile")
            if (nodes.length == 0) null
            else (nodes.item(0) as Element).getAttribute("full-path")
        } catch (e: Exception) {
            null
        }
    }

    private fun readXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(file)

    private fun resolveInside(root: File, parent: File, name: String): File? =
        File(parent, name).canonicalFile.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }

    private fun unzip(epub: File, dir: File, maxExpandedBytes: Long) {
        ZipFile(epub).use { zip ->
            if (maxExpandedBytes != Long.MAX_VALUE && zip.size() > 10_000) throw IOException("Too many EPUB entries")
            val root = dir.canonicalFile
            val buffer = ByteArray(128 * 1024)
            var remaining = maxExpandedBytes
            for (entry in zip.entries()) {
                val target = File(dir, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > remaining) throw IOException("EPUB is too large")
                                out.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                }
            }
        }
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String?,
        val properties: String?
    )
}

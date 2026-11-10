package com.cloudin.reader

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubChapter(val title: String, val file: File)

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
        val chapters = mutableListOf<EpubChapter>()
        val spineNodes = doc.getElementsByTagName("itemref")
        for (i in 0 until spineNodes.length) {
            val e = spineNodes.item(i) as? Element ?: continue
            val idref = e.getAttribute("idref")
            val item = items[idref] ?: continue
            if (item.mediaType != null && !item.mediaType.contains("html")) continue
            val f = resolveInside(dir, opfDir, item.href) ?: continue
            if (!f.isFile) continue
            chapters.add(EpubChapter("第${chapters.size + 1}章", f))
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

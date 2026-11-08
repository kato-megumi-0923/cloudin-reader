package com.cloudin.reader

import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
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
    fun parse(epub: File, dir: File, coversDir: File, bookId: String): EpubInfo? {
        return try {
            dir.mkdirs()
            unzip(epub, dir)
            readInfo(dir, coversDir, bookId)
        } catch (e: Exception) {
            null
        }
    }

    fun readInfo(dir: File, coversDir: File? = null, bookId: String? = null): EpubInfo? {
        val container = File(dir, "META-INF/container.xml")
        if (!container.exists()) return null
        val rootPath = parseContainer(container) ?: return null
        val opf = File(dir, rootPath)
        if (!opf.exists()) return null
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(opf)
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
            val f = File(opfDir, item.href)
            if (!f.exists()) continue
            chapters.add(EpubChapter("第${chapters.size + 1}章", f))
        }
        if (chapters.isEmpty()) return null

        var coverPath: String? = null
        if (coverHref != null && coversDir != null && bookId != null) {
            val src = File(opfDir, coverHref!!)
            if (src.exists()) {
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
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(container)
            val nodes = doc.getElementsByTagName("rootfile")
            if (nodes.length == 0) null
            else (nodes.item(0) as Element).getAttribute("full-path")
        } catch (e: Exception) {
            null
        }
    }

    private fun unzip(epub: File, dir: File) {
        ZipFile(epub).use { zip ->
            val root = dir.canonicalFile
            for (entry in zip.entries()) {
                val target = File(dir, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) }
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

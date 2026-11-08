package com.cloudin.reader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,          // "txt" or "epub"
    val path: String,            // absolute path of the book file / epub dir
    val coverPath: String?,
    var progress: Float,
    var lastRead: Long,
    val addedAt: Long,
    val charset: String?,
    var hash: String? = null,    // SHA-256 of the source file, used for de-duplication
    var favorite: Boolean = false,
    var groupId: String? = null,
    var pinned: Boolean = false
)

sealed class ImportResult {
    data class Added(val book: Book) : ImportResult()
    object Duplicate : ImportResult()
    object Failed : ImportResult()
}

object BookStore {

    private const val FILE_NAME = "library.json"

    fun repairPath(context: Context, path: String): String {
        val f = File(path)
        if (f.exists()) return path
        val name = f.name
        val candidate = File(booksDir(context), name)
        if (candidate.exists()) return candidate.absolutePath
        val cover = File(File(context.filesDir, "covers"), name)
        if (cover.exists()) return cover.absolutePath
        return path
    }

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").apply { mkdirs() }

    fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    private fun libraryFile(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): MutableList<Book> {
        val f = libraryFile(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Book>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Book(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        author = o.optString("author", ""),
                        format = o.getString("format"),
                        path = repairPath(context, o.getString("path")),
                        coverPath = if (o.isNull("cover")) null else repairPath(context, o.getString("cover")!!),
                        progress = o.optDouble("progress", 0.0).toFloat(),
                        lastRead = o.optLong("lastRead", 0L),
                        addedAt = o.optLong("addedAt", 0L),
                        charset = if (o.isNull("charset")) null else o.getString("charset"),
                        hash = if (o.isNull("hash")) null else o.getString("hash"),
                        favorite = o.optBoolean("favorite", false),
                        pinned = o.optBoolean("pinned", false),
                        groupId = if (o.isNull("groupId")) null else o.getString("groupId")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, books: List<Book>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("author", b.author)
                put("format", b.format)
                put("path", b.path)
                put("cover", b.coverPath)
                put("progress", b.progress.toDouble())
                put("lastRead", b.lastRead)
                put("addedAt", b.addedAt)
                put("charset", b.charset)
                put("hash", b.hash)
                put("favorite", b.favorite)
                put("pinned", b.pinned)
                put("groupId", b.groupId)
            })
        }
        libraryFile(context).writeText(arr.toString())
    }

    fun setFavorite(context: Context, bookId: String, favorite: Boolean) {
        val list = load(context)
        list.firstOrNull { it.id == bookId }?.favorite = favorite
        save(context, list)
    }

    fun setPinned(context: Context, bookId: String, pinned: Boolean) {
        val list = load(context)
        list.firstOrNull { it.id == bookId }?.pinned = pinned
        save(context, list)
    }

    fun setGroup(context: Context, bookIds: Collection<String>, groupId: String?) {
        val ids = bookIds.toSet()
        val list = load(context)
        list.forEach { if (it.id in ids) it.groupId = groupId }
        save(context, list)
    }

    fun updateProgress(context: Context, bookId: String, progress: Float) {
        val list = load(context)
        val b = list.firstOrNull { it.id == bookId } ?: return
        b.progress = progress.coerceIn(0f, 1f)
        b.lastRead = System.currentTimeMillis()
        save(context, list)
    }

    fun import(context: Context, uri: Uri, displayName: String?): ImportResult {
        val name = displayName ?: "book"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "txt" && ext != "epub") return ImportResult.Failed

        val id = UUID.randomUUID().toString().replace("-", "")
        val target = File(booksDir(context), "$id.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) }
            } ?: return ImportResult.Failed
        } catch (e: Exception) {
            target.delete()
            return ImportResult.Failed
        }

        val hash = sha256(target) ?: run {
            target.delete()
            return ImportResult.Failed
        }

        // 补算历史书目的哈希，再判断是否重复
        val existing = load(context)
        var hashFilled = false
        existing.forEach { b ->
            if (b.hash == null) {
                b.hash = bookHash(context, b)
                if (b.hash != null) hashFilled = true
            }
        }
        if (existing.any { it.hash != null && it.hash == hash }) {
            target.delete()
            if (hashFilled) save(context, existing)
            return ImportResult.Duplicate
        }

        val book: Book = if (ext == "txt") {
            Book(
                id = id,
                title = name.substringBeforeLast('.').trim().ifEmpty { "未命名" },
                author = "",
                format = "txt",
                path = target.absolutePath,
                coverPath = null,
                progress = 0f,
                lastRead = 0L,
                addedAt = System.currentTimeMillis(),
                charset = detectCharset(target),
                hash = hash
            )
        } else {
            val dir = File(booksDir(context), "${id}_epub")
            val info = EpubParser.parse(target, dir, coversDir(context), id)
            if (info == null) {
                target.delete()
                dir.deleteRecursively()
                return ImportResult.Failed
            }
            Book(
                id = id,
                title = info.title.ifBlank { name.substringBeforeLast('.').trim() },
                author = info.author,
                format = "epub",
                path = dir.absolutePath,
                coverPath = info.coverPath,
                progress = 0f,
                lastRead = 0L,
                addedAt = System.currentTimeMillis(),
                charset = null,
                hash = hash
            )
        }
        if (hashFilled) save(context, existing)
        return ImportResult.Added(book)
    }

    fun remove(context: Context, book: Book) {
        val list = load(context)
        list.removeAll { it.id == book.id }
        save(context, list)
        runCatching {
            if (book.format == "epub") File(book.path).deleteRecursively()
            else File(book.path).delete()
            book.coverPath?.let { File(it).delete() }
        }
    }

    /** 计算已有书目的内容哈希；epub 的源文件按 id 命名保存在 books 目录。 */
    fun bookHash(context: Context, book: Book): String? {
        return when (book.format) {
            "epub" -> sha256(File(booksDir(context), "${book.id}.epub"))
            else -> sha256(File(book.path))
        }
    }

    private fun sha256(file: File): String? = runCatching {
        if (!file.exists()) return null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Sniff the charset from a leading sample. */
    private fun detectCharset(file: File): String {
        val size = minOf(file.length(), 128L * 1024).toInt()
        val sample = ByteArray(size)
        file.inputStream().use { it.read(sample) }
        return TextCodec.detectName(sample)
    }
}
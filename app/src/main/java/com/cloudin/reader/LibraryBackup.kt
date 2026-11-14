package com.cloudin.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

/**
 * 书库的独立校验备份。
 *
 * 只看结构是否可信，不看条目数——批量删书会让条目数合法骤降，不能当损坏判据。
 * 恢复时还要逐条确认正文文件还在，避免把「已经删掉的书」重新列回书架。
 */
internal object LibraryBackup {

    private const val FILE_NAME = "library.backup.json"

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun write(context: Context, books: List<Book>) {
        runCatching {
            val atomic = AtomicFile(file(context))
            val stream = atomic.startWrite()
            try {
                stream.write(BookStore.encode(books).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    fun readValidated(context: Context): List<Book>? {
        val target = file(context)
        if (!target.exists()) return null
        val raw = runCatching {
            AtomicFile(target).openRead().bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return validate(context, raw)
    }

    fun validate(context: Context, raw: String): List<Book>? = runCatching {
        val array = JSONArray(raw)
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val entry = array.getJSONObject(index)
            val id = entry.opt("id") as? String ?: return null
            val title = entry.opt("title") as? String ?: return null
            val path = entry.opt("path") as? String ?: return null
            val format = entry.opt("format")
            if (id.isBlank() || !ids.add(id) || title.isBlank() || !File(path).isAbsolute ||
                (format != "txt" && format != "epub")
            ) return null
            for (field in listOf("progress", "lastRead", "addedAt")) {
                if (!entry.has(field)) continue
                val number = (entry.opt(field) as? Number)?.toDouble() ?: return null
                if (!number.isFinite() || number < 0 || (field == "progress" && number > 1)) return null
            }
            if (!entry.isNull("cover") && entry.opt("cover") !is String) return null
        }
        BookStore.decode(context, raw, repairPaths = false)
    }.getOrNull()

    fun resolveBook(context: Context, book: Book): Book? = runCatching {
        val currentBooksDir = BookStore.booksDir(context)
        val booksDir = currentBooksDir.canonicalFile
        val original = File(book.path).canonicalFile
        val expectedName = if (book.format == "epub") "${book.id}_epub" else "${book.id}.txt"
        val relocated = File(currentBooksDir, expectedName)
        val useOriginal = original.isInside(booksDir) && original.exists()
        val target = if (useOriginal) original else relocated.canonicalFile
        if (!useOriginal && (target.parentFile != booksDir || target.name != expectedName)) return null
        val readable = if (book.format == "epub") {
            EpubParser.readInfo(target)?.chapters?.any { it.file.isFile && it.file.canRead() } == true
        } else {
            target.isFile && target.canRead()
        }
        if (!readable) return null
        val resolved = if (useOriginal) book else book.copy(path = relocated.absolutePath)
        if (!useOriginal && book.hash != null && book.format == "txt" &&
            BookStore.bookHash(context, resolved) != book.hash
        ) return null
        val coversDir = BookStore.coversDir(context).canonicalFile
        val cover = book.coverPath?.let { File(it).canonicalFile }
            ?.takeIf { it.isInside(coversDir) && it.isFile }
        resolved.copy(coverPath = book.coverPath.takeIf { cover != null })
    }.getOrNull()

    private fun File.isInside(directory: File): Boolean = path.startsWith(directory.path + File.separator)
}

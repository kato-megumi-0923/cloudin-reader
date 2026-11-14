package com.cloudin.reader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

internal const val DEFAULT_COVER_COUNT = 165

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
    var pinned: Boolean = false,
    var readingOffset: Int? = null,
    var defaultCoverIndex: Int? = null
)

sealed class ImportResult {
    data class Added(val book: Book) : ImportResult()
    object Duplicate : ImportResult()
    object Failed : ImportResult()
}

/** 书库读取结果：必须区分「没有书库」和「读不出来」，后者一律禁止写入。 */
internal sealed interface LibraryLoad {
    data class Ok(val books: MutableList<Book>) : LibraryLoad
    data object Missing : LibraryLoad
    data object Failed : LibraryLoad
}

internal data class RestoreReport(
    val restored: Int,
    val dropped: List<String>,
    val ok: Boolean,
    val message: String = ""
)

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

    @Synchronized
    internal fun read(context: Context): LibraryLoad {
        val target = libraryFile(context)
        if (!target.exists() && !File(target.path + ".bak").exists()) return LibraryLoad.Missing
        val books = try {
            decode(context, AtomicFile(target).openRead().bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            keepCorruptCopy(context, target)
            return LibraryLoad.Failed
        }
        if (assignDefaultCovers(books, candidates = DefaultCoverStore.enabledIndexes(context))) {
            runCatching { save(context, books) }
        }
        return LibraryLoad.Ok(books)
    }

    /**
     * 只读展示用：失败时返回空列表，但绝不会有任何写入口拿这个结果去覆盖原文件。
     * 所有会写盘的地方必须走 [read] + [mutate]。
     */
    fun load(context: Context): MutableList<Book> = when (val result = read(context)) {
        is LibraryLoad.Ok -> result.books
        LibraryLoad.Missing -> mutableListOf()
        LibraryLoad.Failed -> mutableListOf()
    }

    private fun keepCorruptCopy(context: Context, target: File) {
        runCatching {
            target.copyTo(File(context.filesDir, "library.corrupt.${System.currentTimeMillis()}.json"), overwrite = true)
        }
    }

    internal fun decode(context: Context, raw: String, repairPaths: Boolean = true): MutableList<Book> {
        val arr = JSONArray(raw)
        val list = mutableListOf<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Book(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    author = o.optString("author", ""),
                    format = o.getString("format"),
                    path = o.getString("path").let { if (repairPaths) repairPath(context, it) else it },
                    coverPath = if (o.isNull("cover")) null else o.getString("cover").let {
                        if (repairPaths) repairPath(context, it) else it
                    },
                    progress = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                    lastRead = o.optLong("lastRead", 0L),
                    addedAt = o.optLong("addedAt", 0L),
                    charset = if (o.isNull("charset")) null else o.getString("charset"),
                    hash = if (o.isNull("hash")) null else o.getString("hash"),
                    favorite = o.optBoolean("favorite", false),
                    pinned = o.optBoolean("pinned", false),
                    groupId = if (o.isNull("groupId")) null else o.getString("groupId"),
                    readingOffset = o.optInt("readingOffset", -1).takeIf { it >= 0 },
                    // 内置封面的编号固定在 0..164；新增封面从 165 起顺延，不随删除重新编号。
                    defaultCoverIndex = o.optInt("defaultCoverIndex", -1)
                        .takeIf { it >= 0 && o.optInt("defaultCoverPoolSize") == DEFAULT_COVER_COUNT }
                )
            )
        }
        return list
    }

    internal fun encode(books: List<Book>): String {
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
                put("readingOffset", b.readingOffset)
                put("defaultCoverIndex", b.defaultCoverIndex)
                if (b.defaultCoverIndex != null) put("defaultCoverPoolSize", DEFAULT_COVER_COUNT)
            })
        }
        return arr.toString()
    }

    /**
     * 给没有真实封面的书分配默认封面编号。
     *
     * 规则：永远在「当前使用次数最少」的候选里随机挑一个，保持分配均匀，
     * 又不会让连续导入的一批书拿到 0、1、2… 这样的连号。
     * [candidates] 为当前可用的封面编号（用户在设置里删掉的不在其中）。
     */
    internal fun assignDefaultCovers(
        books: List<Book>,
        random: Random = Random.Default,
        candidates: List<Int> = (0 until DEFAULT_COVER_COUNT).toList()
    ): Boolean {
        val pool = candidates.ifEmpty { (0 until DEFAULT_COVER_COUNT).toList() }
        val usage = HashMap<Int, Int>(pool.size)
        pool.forEach { usage[it] = 0 }
        val unassigned = mutableListOf<Book>()
        for (book in books) {
            if (book.coverPath != null) continue
            val index = book.defaultCoverIndex
            if (index == null || !usage.containsKey(index)) unassigned.add(book)
            else usage[index] = (usage[index] ?: 0) + 1
        }
        if (unassigned.isEmpty()) return false
        for (book in unassigned) {
            val least = usage.values.min()
            val leastIndexes = usage.filterValues { it == least }.keys.sorted()
            val chosen = leastIndexes[random.nextInt(leastIndexes.size)]
            book.defaultCoverIndex = chosen
            usage[chosen] = least + 1
        }
        return true
    }

    private fun save(context: Context, books: List<Book>, writeBackup: Boolean = true) {
        val atomic = AtomicFile(libraryFile(context))
        val stream = atomic.startWrite()
        try {
            stream.write(encode(books).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        if (writeBackup) LibraryBackup.write(context, books)
    }

    /** 所有写入口的统一入口：读失败就什么都不做，避免拿空列表覆盖书库。 */
    private inline fun mutate(context: Context, writeBackup: Boolean = true, block: (MutableList<Book>) -> Unit): Boolean {
        val books = when (val result = read(context)) {
            is LibraryLoad.Ok -> result.books
            LibraryLoad.Missing -> mutableListOf()
            LibraryLoad.Failed -> return false
        }
        block(books)
        return runCatching { save(context, books, writeBackup); true }.getOrDefault(false)
    }

    @Synchronized
    fun setFavorite(context: Context, bookId: String, favorite: Boolean): Boolean = mutate(context) { books ->
        books.firstOrNull { it.id == bookId }?.favorite = favorite
    }

    @Synchronized
    fun setPinned(context: Context, bookIds: Collection<String>, pinned: Boolean): Boolean = mutate(context) { books ->
        val ids = bookIds.toSet()
        books.forEach { if (it.id in ids) it.pinned = pinned }
    }

    @Synchronized
    fun setGroup(context: Context, bookIds: Collection<String>, groupId: String?): Boolean = mutate(context) { books ->
        val ids = bookIds.toSet()
        books.forEach { if (it.id in ids) it.groupId = groupId }
    }

    @Synchronized
    fun clearGroup(context: Context, groupId: String): Boolean = mutate(context) { books ->
        books.forEach { if (it.groupId == groupId) it.groupId = null }
    }

    @Synchronized
    fun updateProgress(context: Context, bookId: String, progress: Float, offset: Int? = null): Boolean =
        // 翻页是热路径，不跟着写备份，避免每页两次落盘。
        mutate(context, writeBackup = false) { books ->
            books.firstOrNull { it.id == bookId }?.let { book ->
                book.progress = progress.coerceIn(0f, 1f)
                book.readingOffset = offset
                book.lastRead = System.currentTimeMillis()
            }
        }

    fun import(
        context: Context,
        uri: Uri,
        displayName: String?,
        maxExpandedBytes: Long = Long.MAX_VALUE,
        onImported: ((Book) -> Unit)? = null
    ): ImportResult {
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

        // 文件读取和哈希计算不持锁；提交时重新读取书库，避免覆盖前台操作。
        val hashes = missingHashes(context)

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
            val info = EpubParser.parse(target, dir, coversDir(context), id, maxExpandedBytes)
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
        val (result, importedBook) = synchronized(this) {
            val existing = when (val loaded = read(context)) {
                is LibraryLoad.Ok -> loaded.books
                LibraryLoad.Missing -> mutableListOf()
                LibraryLoad.Failed -> return@synchronized ImportResult.Failed to book
            }
            existing.forEach { if (it.hash == null) it.hash = hashes[it.id] }
            val duplicate = existing.firstOrNull { it.hash == hash }
            if (duplicate != null) {
                deleteFiles(context, book)
                runCatching { save(context, existing) }
                ImportResult.Duplicate to duplicate
            } else {
                existing.add(book)
                assignDefaultCovers(existing, candidates = DefaultCoverStore.enabledIndexes(context))
                try {
                    save(context, existing)
                } catch (e: Exception) {
                    deleteFiles(context, book)
                    throw e
                }
                ImportResult.Added(book) to book
            }
        }
        if (result is ImportResult.Failed) {
            deleteFiles(context, book)
            return ImportResult.Failed
        }
        onImported?.invoke(importedBook)
        return result
    }

    @Synchronized
    fun remove(context: Context, book: Book): Boolean {
        val books = when (val result = read(context)) {
            is LibraryLoad.Ok -> result.books
            LibraryLoad.Missing -> return false
            LibraryLoad.Failed -> return false
        }
        if (books.none { it.id == book.id }) return false
        books.removeAll { it.id == book.id }
        val saved = runCatching { save(context, books); true }.getOrDefault(false)
        if (!saved) return false
        deleteFiles(context, book)
        return true
    }

    private fun deleteFiles(context: Context, book: Book) {
        runCatching {
            if (book.format == "epub") {
                File(book.path).deleteRecursively()
                File(booksDir(context), "${book.id}.epub").delete()
            } else File(book.path).delete()
            book.coverPath?.let { File(it).delete() }
        }
    }

    private fun missingHashes(context: Context): Map<String, String?> {
        val books = (read(context) as? LibraryLoad.Ok)?.books ?: return emptyMap()
        return books.filter { it.hash == null }.associate { it.id to bookHash(context, it) }
    }

    fun cleanupDuplicates(context: Context): Int {
        val hashes = missingHashes(context)
        return synchronized(this) {
            val books = (read(context) as? LibraryLoad.Ok)?.books ?: return@synchronized 0
            books.forEach { if (it.hash == null) it.hash = hashes[it.id] }
            val removed = books.filter { it.hash != null }.groupBy { it.hash }.values.flatMap { group ->
                val keep = group.maxByOrNull { it.lastRead }
                group.filter { it.id != keep?.id }
            }
            val ids = removed.map { it.id }.toSet()
            if (ids.isEmpty()) return@synchronized 0
            val saved = runCatching { save(context, books.filter { it.id !in ids }); true }.getOrDefault(false)
            if (!saved) return@synchronized 0
            removed.forEach { deleteFiles(context, it) }
            removed.size
        }
    }

    /**
     * 用户删掉某些默认封面后，把指着它们的书重新分配到还在池里的封面上。
     */
    @Synchronized
    fun reassignRemovedCovers(context: Context, removed: Collection<Int>): Boolean {
        if (removed.isEmpty()) return false
        val targets = removed.toSet()
        return mutate(context) { books ->
            books.forEach { if (it.defaultCoverIndex in targets) it.defaultCoverIndex = null }
            assignDefaultCovers(books, candidates = DefaultCoverStore.enabledIndexes(context))
        }
    }

    /**
     * 清理指向不存在分组的 groupId。
     * 调用方必须确认分组文件读取成功（[GroupLoad.Ok]）才能调，否则一次读盘异常就会把正常归属清空。
     */
    @Synchronized
    fun clearDanglingGroups(context: Context, validGroupIds: Set<String>): Int {
        var cleared = 0
        mutate(context) { books ->
            books.forEach { book ->
                if (book.groupId != null && book.groupId !in validGroupIds) {
                    book.groupId = null
                    cleared++
                }
            }
        }
        return cleared
    }

    /** 「同时从书架删除」一次性提交，不逐本读写整库。 */
    @Synchronized
    internal fun removeByGroup(context: Context, groupId: String): Boolean {
        val books = when (val result = read(context)) {
            is LibraryLoad.Ok -> result.books
            LibraryLoad.Missing -> return true
            LibraryLoad.Failed -> return false
        }
        val targets = books.filter { it.groupId == groupId }
        if (targets.isEmpty()) return true
        books.removeAll(targets)
        val saved = runCatching { save(context, books); true }.getOrDefault(false)
        if (!saved) return false
        targets.forEach { deleteFiles(context, it) }
        return true
    }

    /**
     * 用独立校验备份恢复书库。
     * 备份的合法性只看结构，不看条目数；恢复时逐条确认正文还在，缺正文的书直接剔除。
     */
    @Synchronized
    internal fun restore(context: Context): RestoreReport {
        if (read(context) is LibraryLoad.Ok) {
            return RestoreReport(0, emptyList(), false, "当前书库可以读取")
        }
        val backup = LibraryBackup.readValidated(context)
            ?: return RestoreReport(0, emptyList(), false, "备份不存在或已损坏")
        val dropped = mutableListOf<String>()
        val kept = backup.mapNotNull { book ->
            LibraryBackup.resolveBook(context, book).also { if (it == null) dropped.add(book.title) }
        }
        if (backup.isNotEmpty() && kept.isEmpty()) {
            return RestoreReport(0, dropped, false, "备份里的书都已不在磁盘上或无法读取")
        }
        val written = runCatching { save(context, kept, writeBackup = false); true }.getOrDefault(false)
        return if (written) RestoreReport(kept.size, dropped, true) else RestoreReport(0, dropped, false, "写入书库失败")
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

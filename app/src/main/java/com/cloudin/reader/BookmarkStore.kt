package com.cloudin.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** 一条书签：记录字符偏移、所在章节和一小段上下文，用于列表展示。 */
data class Bookmark(
    val id: String,
    val offset: Int,
    val chapter: String,
    val snippet: String,
    val createdAt: Long
)

/** 添加书签的结果：位置重复和存盘失败要分开，否则会把「写失败」误报成「已有书签」。 */
internal enum class BookmarkAddResult { ADDED, DUPLICATE, FAILED }

/** 按书存放书签，落在 files/bookmarks.json；数据可丢，丢了不影响阅读。 */
object BookmarkStore {

    private const val FILE_NAME = "bookmarks.json"

    /** 同一位置附近再点一次「添加书签」，认作同一条，避免堆叠。 */
    private const val SAME_POSITION_WINDOW = 200

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context, bookId: String): List<Bookmark> = readAll(context)[bookId].orEmpty()

    /** 返回结果区分「已加」「重复位置」「写盘失败」。 */
    @Synchronized
    internal fun add(
        context: Context,
        bookId: String,
        offset: Int,
        chapter: String,
        snippet: String,
        pageRange: IntRange? = null
    ): BookmarkAddResult {
        val all = readAllOrNull(context) ?: return BookmarkAddResult.FAILED
        val list = all[bookId]?.toMutableList() ?: mutableListOf()
        // 同一页只会跳到同一个位置，按整页判重；没给页范围时退回固定窗口
        val duplicated = if (pageRange != null) {
            list.any { it.offset in pageRange }
        } else {
            list.any { abs(it.offset - offset) < SAME_POSITION_WINDOW }
        }
        if (duplicated) return BookmarkAddResult.DUPLICATE
        list.add(
            Bookmark(
                id = UUID.randomUUID().toString(),
                offset = offset,
                chapter = chapter,
                snippet = snippet,
                createdAt = System.currentTimeMillis()
            )
        )
        list.sortBy { it.offset }
        all[bookId] = list
        return if (writeAll(context, all)) BookmarkAddResult.ADDED else BookmarkAddResult.FAILED
    }

    /** 当前阅读页内是否已有书签；用于把按钮切成「已加书签」。优先按页范围判定。 */
    @Synchronized
    fun findNear(context: Context, bookId: String, offset: Int, pageRange: IntRange? = null): Bookmark? {
        val list = load(context, bookId)
        if (pageRange != null) {
            list.firstOrNull { it.offset in pageRange }?.let { return it }
        }
        return list.firstOrNull { abs(it.offset - offset) < SAME_POSITION_WINDOW }
    }

    @Synchronized
    fun remove(context: Context, bookId: String, id: String): Boolean {
        val all = readAllOrNull(context) ?: return false
        val list = all[bookId]?.toMutableList() ?: return false
        if (!list.removeAll { it.id == id }) return false
        all[bookId] = list
        return writeAll(context, all)
    }

    private fun readAll(context: Context): MutableMap<String, MutableList<Bookmark>> =
        readAllOrNull(context) ?: mutableMapOf()

    /** 读失败返回 null：调用方据此拒绝写入，避免把坏文件覆盖成「只剩一条新书签」。 */
    private fun readAllOrNull(context: Context): MutableMap<String, MutableList<Bookmark>>? {
        val f = file(context)
        if (!f.exists() && !File(f.path + ".bak").exists()) return mutableMapOf()
        return try {
            val root = JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
            val result = mutableMapOf<String, MutableList<Bookmark>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val bookId = keys.next()
                val array = root.optJSONArray(bookId) ?: continue
                val list = mutableListOf<Bookmark>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    list.add(
                        Bookmark(
                            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                            offset = o.optInt("offset", 0),
                            chapter = o.optString("chapter", ""),
                            snippet = o.optString("snippet", ""),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
                result[bookId] = list
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun writeAll(context: Context, all: Map<String, List<Bookmark>>): Boolean = runCatching {
        val root = JSONObject()
        all.forEach { (bookId, marks) ->
            if (marks.isEmpty()) return@forEach
            val array = JSONArray()
            marks.forEach { mark ->
                array.put(JSONObject().apply {
                    put("id", mark.id)
                    put("offset", mark.offset)
                    put("chapter", mark.chapter)
                    put("snippet", mark.snippet)
                    put("createdAt", mark.createdAt)
                })
            }
            root.put(bookId, array)
        }
        val target = AtomicFile(file(context))
        val stream = target.startWrite()
        try {
            stream.bufferedWriter().use { it.write(root.toString()) }
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
        true
    }.getOrDefault(false)
}

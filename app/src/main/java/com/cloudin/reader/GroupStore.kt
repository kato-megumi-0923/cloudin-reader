package com.cloudin.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookGroup(val id: String, val name: String, val createdAt: Long)

/** 分组读取结果；读失败时必须保留书籍归属、不写盘。 */
internal sealed interface GroupLoad {
    data class Ok(val groups: MutableList<BookGroup>) : GroupLoad
    data object Missing : GroupLoad
    data object Failed : GroupLoad
}

object GroupStore {

    private const val FILE_NAME = "groups.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    internal fun read(context: Context): GroupLoad {
        val target = file(context)
        if (!target.exists()) return GroupLoad.Missing
        val groups = try {
            val arr = JSONArray(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            val list = mutableListOf<BookGroup>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BookGroup(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            return GroupLoad.Failed
        }
        return GroupLoad.Ok(groups)
    }

    /** 只读展示用；写入口统一走 [read] 判断。 */
    fun load(context: Context): MutableList<BookGroup> = when (val result = read(context)) {
        is GroupLoad.Ok -> result.groups
        GroupLoad.Missing, GroupLoad.Failed -> mutableListOf()
    }

    private fun save(context: Context, groups: List<BookGroup>): Boolean = runCatching {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("createdAt", g.createdAt)
            })
        }
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(arr.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        true
    }.getOrDefault(false)

    @Synchronized
    fun add(context: Context, name: String): BookGroup? {
        val groups = when (val result = read(context)) {
            is GroupLoad.Ok -> result.groups
            GroupLoad.Missing -> mutableListOf()
            GroupLoad.Failed -> return null
        }
        val group = BookGroup(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifEmpty { "未命名分组" },
            createdAt = System.currentTimeMillis()
        )
        groups.add(group)
        return if (save(context, groups)) group else null
    }

    @Synchronized
    fun groupImportedBooks(context: Context, directoryName: String, bookIds: Collection<String>): BookGroup? {
        if (bookIds.isEmpty()) return null
        val name = directoryName.trim().ifEmpty { "导入目录" }
        val existing = load(context).firstOrNull { it.name == name }
        val group = existing ?: add(context, name) ?: return null
        if (BookStore.setGroup(context, bookIds, group.id)) return group
        if (existing == null) {
            val current = read(context)
            if (current is GroupLoad.Ok) save(context, current.groups.filterNot { it.id == group.id })
        }
        return null
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String): Boolean {
        val groups = when (val result = read(context)) {
            is GroupLoad.Ok -> result.groups
            GroupLoad.Missing, GroupLoad.Failed -> return false
        }
        val idx = groups.indexOfFirst { it.id == id }
        if (idx < 0) return false
        groups[idx] = groups[idx].copy(name = name.trim().ifEmpty { groups[idx].name })
        return save(context, groups)
    }

    /**
     * 先清书籍归属，再删分组。
     * 中途失败最多留下一个空分组（无害、可再删），反过来则会留下指向不存在分组的 groupId。
     */
    @Synchronized
    fun remove(context: Context, id: String, deleteBooks: Boolean = false): Boolean {
        val groups = when (val result = read(context)) {
            is GroupLoad.Ok -> result.groups
            GroupLoad.Missing, GroupLoad.Failed -> return false
        }
        if (!groups.removeAll { it.id == id }) return false
        val changed = if (deleteBooks) BookStore.removeByGroup(context, id)
            else BookStore.clearGroup(context, id)
        if (!changed) return false
        return save(context, groups)
    }
}

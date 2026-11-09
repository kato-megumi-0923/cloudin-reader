package com.cloudin.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookGroup(val id: String, val name: String, val createdAt: Long)

object GroupStore {

    private const val FILE_NAME = "groups.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): MutableList<BookGroup> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
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
            mutableListOf()
        }
    }

    fun save(context: Context, groups: List<BookGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("createdAt", g.createdAt)
            })
        }
        file(context).writeText(arr.toString())
    }

    fun add(context: Context, name: String): BookGroup {
        val group = BookGroup(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifEmpty { "未命名分组" },
            createdAt = System.currentTimeMillis()
        )
        val list = load(context)
        list.add(group)
        save(context, list)
        return group
    }

    fun rename(context: Context, id: String, name: String) {
        val list = load(context)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.trim().ifEmpty { list[idx].name })
            save(context, list)
        }
    }

    fun remove(context: Context, id: String) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
        BookStore.clearGroup(context, id)
    }
}

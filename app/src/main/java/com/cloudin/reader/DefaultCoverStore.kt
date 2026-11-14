package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal data class CustomCover(val index: Int, val file: String, val addedAt: Long)

/**
 * 默认封面池：内置的 [DEFAULT_COVER_COUNT] 张按固定编号使用，删掉的记在 removed 里，
 * 编号不变，所以书库里的 defaultCoverIndex 不会因为删封面而整体错位。
 * 用户新增的封面从 [DEFAULT_COVER_COUNT] 开始顺延编号，文件存在应用私有目录。
 */
internal class CoverPool(
    val removed: MutableSet<Int> = mutableSetOf(),
    val custom: MutableList<CustomCover> = mutableListOf()
) {
    /** 展示顺序：用户新增的排最前（新的在上），其后才是内置封面。 */
    fun enabled(): List<Int> {
        val list = ArrayList<Int>(DEFAULT_COVER_COUNT + custom.size)
        custom.sortedByDescending { it.index }.forEach { list.add(it.index) }
        for (index in 0 until DEFAULT_COVER_COUNT) if (index !in removed) list.add(index)
        return list
    }

    fun nextIndex(): Int =
        maxOf(DEFAULT_COVER_COUNT, (custom.maxOfOrNull { it.index } ?: (DEFAULT_COVER_COUNT - 1)) + 1)
}

internal object DefaultCoverStore {

    private const val FILE_NAME = "default-covers.json"
    private var cache: CoverPool? = null
    private var cacheKey: String? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 缓存必须跟目录绑定：换一个 filesDir（测试、多用户）不能沿用旧池子。 */
    private fun keyFor(context: Context): String = file(context).absolutePath

    fun coversDir(context: Context): File = File(context.filesDir, "default-covers").apply { mkdirs() }

    fun invalidate() {
        cache = null
        cacheKey = null
    }

    /** 读取失败返回 null，调用方必须据此禁止写入，避免把损坏的文件直接覆盖掉。 */
    fun load(context: Context): CoverPool? {
        val key = keyFor(context)
        cache?.let { if (key == cacheKey) return it }
        val target = file(context)
        if (!target.exists()) {
            return CoverPool().also {
                cache = it
                cacheKey = key
            }
        }
        val pool = try {
            val root = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            val removed = mutableSetOf<Int>()
            root.optJSONArray("removed")?.let { array ->
                for (index in 0 until array.length()) {
                    val value = array.optInt(index, -1)
                    if (value in 0 until DEFAULT_COVER_COUNT) removed.add(value)
                }
            }
            val custom = mutableListOf<CustomCover>()
            root.optJSONArray("custom")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val slot = item.optInt("index", -1)
                    val name = item.optString("file", "")
                    if (slot < DEFAULT_COVER_COUNT || name.isEmpty()) continue
                    val stored = File(coversDir(context), name)
                    if (!stored.isFile) continue
                    custom.add(CustomCover(slot, stored.absolutePath, item.optLong("addedAt", 0L)))
                }
            }
            CoverPool(removed, custom)
        } catch (e: Exception) {
            return null
        }
        cache = pool
        cacheKey = key
        return pool
    }

    /** 允许读失败的场景用：宁可当空池，也不让界面崩。 */
    fun pool(context: Context): CoverPool = load(context) ?: CoverPool()

    fun enabledIndexes(context: Context): List<Int> = pool(context).enabled()

    fun resolvePath(context: Context, index: Int?): String? {
        if (index == null || index < DEFAULT_COVER_COUNT) return null
        return pool(context).custom.firstOrNull { it.index == index }?.file
    }

    private fun save(context: Context, pool: CoverPool): Boolean = runCatching {
        val root = JSONObject()
        root.put("removed", JSONArray().apply { pool.removed.sorted().forEach { put(it) } })
        root.put("custom", JSONArray().apply {
            pool.custom.sortedBy { it.index }.forEach { cover ->
                put(JSONObject().apply {
                    put("index", cover.index)
                    put("file", File(cover.file).name)
                    put("addedAt", cover.addedAt)
                })
            }
        })
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        cache = pool
        cacheKey = keyFor(context)
        true
    }.getOrDefault(false)

    /** 新增一张自定义封面，返回它在池里的编号。 */
    fun addCustom(context: Context, bitmap: Bitmap): Int? {
        val pool = load(context) ?: return null
        val index = pool.nextIndex()
        val target = File(coversDir(context), "cover-$index.jpg")
        val written = runCatching {
            FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            true
        }.getOrDefault(false)
        if (!written) {
            runCatching { target.delete() }
            return null
        }
        pool.custom.add(CustomCover(index, target.absolutePath, System.currentTimeMillis()))
        if (!save(context, pool)) {
            pool.custom.removeAll { it.index == index }
            runCatching { target.delete() }
            return null
        }
        return index
    }

    /** 批量删除；池里至少要留一张，否则书架会退回默认渐变。 */
    fun remove(context: Context, indexes: Collection<Int>): Boolean {
        val pool = load(context) ?: return false
        val targets = indexes.toSet()
        if (targets.isEmpty()) return false
        val remaining = pool.enabled().count { it !in targets }
        if (remaining < 1) return false
        val removedCustom = pool.custom.filter { it.index in targets }
        pool.custom.removeAll { it.index in targets }
        targets.filter { it in 0 until DEFAULT_COVER_COUNT }.forEach { pool.removed.add(it) }
        if (!save(context, pool)) {
            pool.custom.addAll(removedCustom)
            return false
        }
        removedCustom.forEach { runCatching { File(it.file).delete() } }
        return true
    }
}

package com.cloudin.reader

import android.os.Build
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 分页缓存：每本书按排版 key 存多份，切回用过的字号/尺寸直接命中。
 * 缓存可以随时丢弃；淘汰按访问时间而不是写入时间，常用排版不会被新缓存挤掉。
 */
internal class PaginationCache(directory: File, private val bookId: String) {

    private val cacheDirectory = File(directory, "reader-pagination")
    private val base = cacheName(bookId)

    fun key(document: Document, layout: String): String = listOf(
        VERSION, Build.FINGERPRINT, bookId, document.text.length,
        document.text.hashCode(), document.images.hashCode(),
        document.chapters.hashCode(), layout
    ).joinToString("\n")

    fun read(key: String, textLength: Int): List<IntRange>? = synchronized(monitor) {
        val slot = slotOf(key)
        readRanges(AtomicFile(slot), key, textLength)?.let {
            touch(slot)
            return it
        }
        // 旧版本每本书只存一份，命中后迁移到按 key 命名的新文件
        legacyCandidates().forEach { legacy ->
            val pages = readRanges(legacy, key, textLength) ?: return@forEach
            if (writeRanges(AtomicFile(slot), key, pages)) {
                legacy.delete()
                touch(slot)
                prune()
            }
            return pages
        }
        return null
    }

    fun write(key: String, pages: List<IntRange>) = synchronized(monitor) {
        val slot = slotOf(key)
        if (writeRanges(AtomicFile(slot), key, pages)) {
            touch(slot)
            prune()
        }
    }

    private fun slotOf(key: String): File =
        File(cacheDirectory, "$base-${digest(key).take(16)}.bin")

    private fun legacyCandidates(): List<AtomicFile> = listOf(
        AtomicFile(File(cacheDirectory, "$base.bin")),
        AtomicFile(File(cacheDirectory, "${bookId.hashCode()}.bin"))
    )

    private fun readRanges(source: AtomicFile, key: String, textLength: Int): List<IntRange>? = runCatching {
        DataInputStream(source.openRead().buffered()).use { input ->
            if (input.readUTF() != key) return null
            val count = input.readInt()
            if (count !in 1..minOf(textLength, MAX_PAGES)) return null
            var previousEnd = 0
            val pages = ArrayList<IntRange>(count)
            repeat(count) {
                val start = input.readInt()
                val end = input.readInt()
                if (start < previousEnd || end <= start || end > textLength) return null
                pages.add(start until end)
                previousEnd = end
            }
            if (input.read() != -1) return null
            pages
        }
    }.getOrNull()

    private fun writeRanges(file: AtomicFile, key: String, pages: List<IntRange>): Boolean {
        if (pages.size !in 1..MAX_PAGES || Thread.currentThread().isInterrupted) return false
        return runCatching {
            val stream = file.startWrite()
            try {
                val output = DataOutputStream(stream.buffered())
                output.writeUTF(key)
                output.writeInt(pages.size)
                for (page in pages) {
                    output.writeInt(page.first)
                    output.writeInt(page.last + 1)
                }
                output.flush()
                file.finishWrite(stream)
                true
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ 访问时间与淘汰

    /**
     * File.lastModified 在部分设备上精度有限，命中时另写一份访问时间表，
     * 淘汰时取两者较新的那个，避免常用排版因为 mtime 最老被清掉。
     */
    private fun accessFile(owner: String): File = File(cacheDirectory, "$owner.access")

    private fun touch(slot: File) {
        val entries = readAccess(base)
        // 只记在访问时间表里，不动物理文件的 mtime：命中不重写缓存文件本身
        entries[slot.name] = maxOf(System.currentTimeMillis(), entries[slot.name] ?: 0L)
        writeAccess(base, entries)
    }

    private fun readAccess(owner: String): MutableMap<String, Long> {
        val file = accessFile(owner)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            val map = mutableMapOf<String, Long>()
            file.forEachLine { line ->
                val separator = line.lastIndexOf(' ')
                if (separator <= 0) return@forEachLine
                line.substring(separator + 1).toLongOrNull()?.let { map[line.substring(0, separator)] = it }
            }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun writeAccess(owner: String, entries: Map<String, Long>) {
        runCatching {
            val file = accessFile(owner)
            if (entries.isEmpty()) file.delete()
            else file.writeText(entries.entries.joinToString("\n") { "${it.key} ${it.value}" })
        }
    }

    private fun prune() {
        val slots = cacheDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(".bin")
        } ?: return
        val owners = slots.associateWith { file ->
            SLOT_NAME.matchEntire(file.name)?.groupValues?.get(1)
        }
        val access = owners.values.filterNotNull().distinct().associateWith(::readAccess)
        val counts = mutableMapOf<String, Int>()
        val updatedOwners = mutableSetOf<String>()
        var bytes = 0L
        val newestFirst = slots.sortedByDescending { file ->
            maxOf(file.lastModified(), access[owners[file]]?.get(file.name) ?: 0L)
        }
        for (file in newestFirst) {
            val owner = owners[file]
            val length = file.length()
            val count = counts[owner] ?: 0
            if (count < MAX_VERSIONS && bytes + length <= MAX_BYTES) {
                bytes += length
                if (owner != null) counts[owner] = count + 1
            } else if (file.delete()) {
                if (owner != null) {
                    access.getValue(owner).remove(file.name)
                    updatedOwners.add(owner)
                }
            } else {
                bytes += length
            }
        }
        updatedOwners.forEach { writeAccess(it, access.getValue(it)) }
    }

    private companion object {
        const val VERSION = 2
        const val MAX_PAGES = 1_000_000
        const val MAX_VERSIONS = 4
        const val MAX_BYTES = 32L * 1024 * 1024
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val SLOT_NAME = Regex("((?:book|sha256)-.+)-[0-9a-f]{16}\\.bin")
        val monitor = Any()

        fun cacheName(bookId: String): String {
            if (SAFE_ID.matches(bookId)) return "book-$bookId"
            return "sha256-${digest(bookId)}"
        }

        fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

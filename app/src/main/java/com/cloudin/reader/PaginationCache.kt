package com.cloudin.reader

import android.os.Build
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Stores the latest set of page ranges per book. The cache can be discarded at any time. */
internal class PaginationCache(directory: File, private val bookId: String) {
    private val cacheDirectory = File(directory, "reader-pagination")
    private val file = AtomicFile(File(cacheDirectory, cacheName(bookId)))

    fun key(document: Document, layout: String): String = listOf(
        VERSION, Build.FINGERPRINT, bookId, document.text.length,
        document.text.hashCode(), document.images.hashCode(), layout
    ).joinToString("\n")

    fun read(key: String, textLength: Int): List<IntRange>? {
        readRanges(file, key, textLength)?.let { return it }
        val legacy = AtomicFile(File(cacheDirectory, "${bookId.hashCode()}.bin"))
        val pages = readRanges(legacy, key, textLength) ?: return null
        if (writeRanges(key, pages)) legacy.delete()
        return pages
    }

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

    fun write(key: String, pages: List<IntRange>) {
        writeRanges(key, pages)
    }

    private fun writeRanges(key: String, pages: List<IntRange>): Boolean {
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

    private companion object {
        const val VERSION = 1
        const val MAX_PAGES = 1_000_000
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")

        fun cacheName(bookId: String): String {
            if (SAFE_ID.matches(bookId)) return "book-$bookId.bin"
            val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray())
            return "sha256-${digest.joinToString("") { "%02x".format(it) }}.bin"
        }
    }
}

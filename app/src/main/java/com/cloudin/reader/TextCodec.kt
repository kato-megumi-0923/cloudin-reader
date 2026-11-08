package com.cloudin.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 编码识别：
 * 1. BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）
 * 2. 严格 UTF-8 解码（容忍结尾被截断的多字节字符）
 * 3. 依次尝试 GB18030 / Big5，用「中文占比」打分选最像的
 */
object TextCodec {

    private val UTF8 = Charsets.UTF_8
    private val GB18030: Charset = runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.ISO_8859_1)
    private val BIG5: Charset = runCatching { Charset.forName("Big5") }.getOrDefault(Charsets.ISO_8859_1)

    fun detectName(bytes: ByteArray): String = detect(bytes).name()

    fun detect(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return UTF8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }

        // 整段严格 UTF-8
        if (decodeStrict(bytes, UTF8) != null) return UTF8
        // 结尾可能截断在多字节字符中间，去掉尾部残缺再试
        val trimmed = trimIncompleteUtf8(bytes)
        if (trimmed.isNotEmpty() && decodeStrict(trimmed, UTF8) != null) return UTF8

        val candidates = listOf(GB18030, BIG5)
        var best = GB18030
        var bestScore = -1.0
        for (cs in candidates) {
            val text = decodeStrict(bytes, cs) ?: continue
            val score = chineseRatio(text)
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        return if (bestScore <= 0.02) GB18030 else best
    }

    fun decode(file: File): String {
        val bytes = file.readBytes()
        var cs = detect(bytes)
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
            cs = UTF8
        } else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            offset = 2
            cs = Charsets.UTF_16LE
        } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            offset = 2
            cs = Charsets.UTF_16BE
        }
        return String(bytes, offset, bytes.size - offset, cs)
            .replace("\uFEFF", "")
    }

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? {
        return try {
            val decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 去掉末尾不完整的 UTF-8 多字节序列（最多 3 字节）。 */
    private fun trimIncompleteUtf8(bytes: ByteArray): ByteArray {
        var cut = 0
        var i = bytes.size - 1
        while (i >= 0 && cut < 3) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                cut++
                i--
            } else {
                break
            }
        }
        if (cut == 0) return bytes
        // 找到该字符的首字节，判断它声明的长度是否超出剩余字节
        val leadIndex = bytes.size - cut - 1
        if (leadIndex < 0) return bytes
        val lead = bytes[leadIndex].toInt() and 0xFF
        val need = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (need > cut + 1) bytes.copyOf(leadIndex) else bytes
    }

    /** 解码结果里汉字/常用标点的占比，用于判断哪种编码更像。 */
    private fun chineseRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        var good = 0
        var bad = 0
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF -> good++
                ch.code in 0x3000..0x303F -> good++
                ch.code in 0xFF00..0xFFEF -> good++
                ch.code < 0x80 -> if (!ch.isISOControl() || ch == '\n' || ch == '\r' || ch == '\t') good++
                ch.code in 0xE000..0xF8FF -> bad += 4
                ch == '\uFFFD' -> bad += 8
                else -> bad += 2
            }
        }
        val total = good + bad
        return if (total == 0) 0.0 else good.toDouble() / total
    }
}

package com.cloudin.reader

import android.graphics.Bitmap

/** 轻量盒式模糊，用于玻璃栏的背景虚化。 */
object Blur {

    fun blurred(src: Bitmap, radius: Int, scale: Float = 0.28f): Bitmap {
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, radius.coerceAtLeast(1))
        boxBlur(pixels, w, h, radius.coerceAtLeast(1))
        boxBlur(pixels, w, h, radius.coerceAtLeast(1))
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(pixels.size)
        // 横向
        for (y in 0 until h) {
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            val row = y * w
            for (i in -r..r) {
                val x = i.coerceIn(0, w - 1)
                val c = pixels[row + x]
                sumA += c ushr 24 and 0xFF
                sumR += c ushr 16 and 0xFF
                sumG += c ushr 8 and 0xFF
                sumB += c and 0xFF
            }
            val div = 2 * r + 1
            for (x in 0 until w) {
                tmp[row + x] = ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val addX = (x + r + 1).coerceAtMost(w - 1)
                val subX = (x - r).coerceAtLeast(0)
                val add = pixels[row + addX]
                val sub = pixels[row + subX]
                sumA += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                sumR += (add ushr 16 and 0xFF) - (sub ushr 16 and 0xFF)
                sumG += (add ushr 8 and 0xFF) - (sub ushr 8 and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }
        // 纵向
        for (x in 0 until w) {
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val y = i.coerceIn(0, h - 1)
                val c = tmp[y * w + x]
                sumA += c ushr 24 and 0xFF
                sumR += c ushr 16 and 0xFF
                sumG += c ushr 8 and 0xFF
                sumB += c and 0xFF
            }
            val div = 2 * r + 1
            for (y in 0 until h) {
                pixels[y * w + x] = ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val addY = (y + r + 1).coerceAtMost(h - 1)
                val subY = (y - r).coerceAtLeast(0)
                val add = tmp[addY * w + x]
                val sub = tmp[subY * w + x]
                sumA += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                sumR += (add ushr 16 and 0xFF) - (sub ushr 16 and 0xFF)
                sumG += (add ushr 8 and 0xFF) - (sub ushr 8 and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}

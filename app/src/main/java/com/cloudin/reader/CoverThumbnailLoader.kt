package com.cloudin.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** Decodes imported covers off the UI thread and shares sized thumbnails between views. */
internal object CoverThumbnailLoader {
    data class Key(val path: String, val maxEdge: Int)
    data class Result(val bitmap: Bitmap?, val isLight: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "CoverThumbnail")
    }
    private val cache = object : LruCache<Key, Result>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Result): Int = value.bitmap?.allocationByteCount ?: 1
    }

    fun key(path: String, width: Int, height: Int): Key {
        val edge = maxOf(width, height)
        val bucket = when {
            edge <= 256 -> 256
            edge <= 512 -> 512
            edge <= 768 -> 768
            else -> 1024
        }
        return Key(path, bucket)
    }

    fun cached(key: Key): Result? = cache.get(key)

    fun load(key: Key, callback: (Result) -> Unit): Future<*> = workers.submit {
        val result = cached(key) ?: runCatching { decode(key) }.getOrNull()
            ?.let { Result(it, sampleLight(it)) } ?: Result(null, false)
        cache.put(key, result)
        main.post { callback(result) }
    }

    private fun decode(key: Key): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / 2 / sample >= key.maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(key.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= key.maxEdge) return bitmap
        val scale = key.maxEdge.toFloat() / edge
        val thumbnail = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        if (thumbnail !== bitmap) bitmap.recycle()
        return thumbnail
    }

    fun sampleLight(bitmap: Bitmap): Boolean {
        var sum = 0.0
        for (y in 0 until 12) {
            for (x in 0 until 12) {
                val pixel = bitmap.getPixel(x * bitmap.width / 12, y * bitmap.height / 12)
                sum += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
            }
        }
        return sum / (255 * 144) > 0.55
    }
}

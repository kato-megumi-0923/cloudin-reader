package com.cloudin.reader

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Decodes covers off the UI thread and shares sized thumbnails between views. */
internal object CoverThumbnailLoader {
    data class Key(val path: String, val maxEdge: Int, val resourceId: Int = 0)
    data class Result(val bitmap: Bitmap?, val isLight: Boolean)

    /** 一次解码请求；同一 key 的并发请求会合并成一次解码。 */
    class Request internal constructor(
        val key: Key,
        internal val callback: (Result) -> Unit
    ) {
        @Volatile
        internal var cancelled = false

        fun cancel() {
            cancelled = true
        }
    }

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
    private val lock = Any()
    private val inFlight = HashMap<Key, MutableList<Request>>()

    fun key(path: String, width: Int, height: Int): Key = Key(path, sizeBucket(width, height))

    fun resourceKey(context: Context, resourceId: Int, width: Int, height: Int): Key =
        Key(context.packageName, sizeBucket(width, height), resourceId)

    private fun sizeBucket(width: Int, height: Int): Int {
        val edge = maxOf(width, height)
        return when {
            edge <= 256 -> 256
            edge <= 512 -> 512
            edge <= 768 -> 768
            else -> 1024
        }
    }

    fun cached(key: Key): Result? = cache.get(key)

    fun load(key: Key, callback: (Result) -> Unit): Request = load(key, callback) {
        decode(key.maxEdge) { BitmapFactory.decodeFile(key.path, it) }
    }

    fun loadResource(resources: Resources, key: Key, callback: (Result) -> Unit): Request = load(key, callback) {
        decode(key.maxEdge) { BitmapFactory.decodeResource(resources, key.resourceId, it) }
    }

    private fun load(key: Key, callback: (Result) -> Unit, decode: () -> Bitmap?): Request {
        val request = Request(key, callback)
        synchronized(lock) {
            cached(key)?.let { result ->
                main.post { if (!request.cancelled) callback(result) }
                return request
            }
            val waiting = inFlight[key]
            if (waiting != null) {
                waiting.add(request)
                return request
            }
            inFlight[key] = mutableListOf(request)
        }
        workers.submit {
            synchronized(lock) {
                if (inFlight[key].orEmpty().all { it.cancelled }) {
                    inFlight.remove(key)
                    return@submit
                }
            }
            val result = runCatching(decode).getOrNull()
                ?.let { Result(it, sampleLight(it)) }
            val batch = synchronized(lock) {
                // 失败结果不写缓存，文件后补上时还能重试
                if (result != null) cache.put(key, result)
                inFlight.remove(key)
            } ?: return@submit
            main.post {
                batch.forEach { req -> if (!req.cancelled) req.callback(result ?: Result(null, false)) }
            }
        }
        return request
    }

    private fun decode(maxEdge: Int, source: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        source(bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / 2 / sample >= maxEdge) sample *= 2
        val bitmap = source(BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: return null
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge
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

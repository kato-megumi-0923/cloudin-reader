package com.cloudin.reader

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.util.concurrent.Executors

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
        decodeSampled(key.maxEdge, scaleToFit = true) { BitmapFactory.decodeFile(key.path, it) }
    }

    fun loadResource(resources: Resources, key: Key, callback: (Result) -> Unit): Request = load(key, callback) {
        decodeSampled(key.maxEdge, scaleToFit = true) { BitmapFactory.decodeResource(resources, key.resourceId, it) }
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

    /** 封面上要不要用深色文字：平均亮度超过 55% 算浅封面。 */
    fun sampleLight(bitmap: Bitmap): Boolean = averageLuminance(bitmap, 12) > 0.55 * 255
}

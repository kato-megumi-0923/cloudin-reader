package com.cloudin.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 位图解码/亮度采样的公共实现。
 *
 * 之前封面缩略图、皮肤照片、默认封面、阅读大图各自写了一份「先量尺寸、再按 inSampleSize 降采样」，
 * 五处代码几乎逐行相同；亮度采样也写了两份。这里收成一套，改采样策略只改这里。
 */

/**
 * 按最大边长降采样解码。
 *
 * @param maxEdge 期望的最大边长（px）
 * @param limit `inSampleSize` 的上限（必须是 2 的幂，走 `sample < limit` 判断）；默认为不限制
 * @param scaleToFit 解码后仍然超过 [maxEdge] 时，再精确缩放一次（代价是可能多生成一张位图）
 * @param source 解码器；会被调用两次（第一次只量尺寸，第二次真正解码），用同一个 [BitmapFactory.Options] 参数区分
 */
internal fun decodeSampled(
    maxEdge: Int,
    limit: Int = Int.MAX_VALUE,
    scaleToFit: Boolean = false,
    source: (BitmapFactory.Options) -> Bitmap?
): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    source(bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null

    var sample = 1
    while (longest / 2 / sample >= maxEdge && sample < limit) sample *= 2

    val bitmap = source(BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }) ?: return null
    if (!scaleToFit) return bitmap

    val edge = maxOf(bitmap.width, bitmap.height)
    if (edge <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / edge
    val thumbnail = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true
    )
    if (thumbnail !== bitmap) bitmap.recycle()
    return thumbnail
}

/** 文件版：`path` 打不开或不是图片时返回 null。 */
internal fun decodeSampledFile(path: String, maxEdge: Int, limit: Int = Int.MAX_VALUE, scaleToFit: Boolean = false): Bitmap? =
    runCatching {
        decodeSampled(maxEdge, limit, scaleToFit) { options -> BitmapFactory.decodeFile(path, options) }
    }.getOrNull()

/**
 * 只读图片尺寸，不分配像素内存。打不开或不是图片时返回 null。
 * 返回 `[width, height]`。
 */
internal fun imageSize(path: String): IntArray? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return intArrayOf(options.outWidth, options.outHeight)
}

/**
 * 平均亮度（0..255）。用 gamma 后的 RGB 加权，和封面/照片的观感一致。
 *
 * @param samples 每边采样点数，实际取 [samples]×[samples] 个点（大图不必逐像素扫）
 */
internal fun averageLuminance(bitmap: Bitmap, samples: Int = 12): Double {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0.0
    val n = samples.coerceAtLeast(1)
    var sum = 0.0
    for (y in 0 until n) {
        val py = (y * bitmap.height / n).coerceAtMost(bitmap.height - 1)
        for (x in 0 until n) {
            val px = (x * bitmap.width / n).coerceAtMost(bitmap.width - 1)
            val pixel = bitmap.getPixel(px, py)
            sum += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
        }
    }
    return sum / (n * n)
}

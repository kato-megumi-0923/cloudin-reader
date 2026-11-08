package com.cloudin.reader

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

/**
 * 牛皮纸底纹：只保留高频细节（纤维 + 颗粒），保证 512px 平铺没有接缝。
 * 大面积明暗和边缘压暗交给 PageView 按整页绘制。
 */
object PaperTexture {

    private var cached: Bitmap? = null
    private const val SIZE = 512

    fun bitmap(): Bitmap {
        cached?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#D9C7A4"))

        // 低频云斑：用周期函数生成，平铺时首尾相接，不会出现接缝
        val lowSize = 128
        val low = Bitmap.createBitmap(lowSize, lowSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(lowSize * lowSize)
        val twoPi = (Math.PI * 2).toFloat()
        for (y in 0 until lowSize) {
            for (x in 0 until lowSize) {
                val n = 0.6f * kotlin.math.sin(twoPi * 3f * x / lowSize) * kotlin.math.cos(twoPi * 2f * y / lowSize) +
                    0.4f * kotlin.math.sin(twoPi * 5f * x / lowSize + 1.1f) * kotlin.math.cos(twoPi * 4f * y / lowSize + 0.6f)
                val a = (kotlin.math.abs(n) * 16f).toInt().coerceIn(0, 16)
                pixels[y * lowSize + x] = if (n > 0)
                    Color.argb(a, 250, 240, 218)
                else
                    Color.argb(a, 164, 140, 104)
            }
        }
        low.setPixels(pixels, 0, lowSize, 0, 0, lowSize, lowSize)
        val scaled = Bitmap.createScaledBitmap(low, SIZE, SIZE, true)
        canvas.drawBitmap(scaled, 0f, 0f, null)

        val random = Random(20261109)

        // 细纤维
        val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(1.4f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0 until 900) {
            val x = random.nextFloat() * SIZE
            val y = random.nextFloat() * SIZE
            val len = 3f + random.nextFloat() * 12f
            val alpha = 4 + random.nextInt(7)
            fiber.color = if (random.nextBoolean())
                Color.argb(alpha, 242, 231, 206)
            else
                Color.argb(alpha, 168, 144, 106)
            fiber.strokeWidth = 0.6f + random.nextFloat() * 0.7f
            canvas.drawLine(x, y, (x + len).coerceAtMost(SIZE.toFloat()), y + (random.nextFloat() - 0.5f), fiber)
        }

        // 纸面颗粒
        val grain = Paint()
        for (i in 0 until 4200) {
            val x = random.nextFloat() * SIZE
            val y = random.nextFloat() * SIZE
            grain.color = if (random.nextBoolean())
                Color.argb(4 + random.nextInt(6), 255, 246, 226)
            else
                Color.argb(4 + random.nextInt(6), 148, 124, 86)
            grain.strokeWidth = 0.7f + random.nextFloat() * 0.6f
            canvas.drawPoint(x, y, grain)
        }

        cached = bmp
        return bmp
    }

    fun shaderPaint(): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}

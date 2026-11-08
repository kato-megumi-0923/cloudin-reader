package com.cloudin.reader

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.random.Random

/**
 * 程序化生成的木纹贴图：柔和的浅木色 + 淡竖纹 + 木节，平铺使用。
 */
object WoodTexture {

    private var cached: Bitmap? = null
    private const val SIZE = 512

    fun bitmap(): Bitmap {
        cached?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val random = Random(20261108)

        // 底色：偏浅的暖木色
        canvas.drawColor(Color.parseColor("#D9B78F"))

        // 宽窄不一的竖条，模拟板材色差
        var x = 0f
        while (x < SIZE) {
            val bandWidth = 14f + random.nextFloat() * 34f
            val light = random.nextBoolean()
            val alpha = 5 + random.nextInt(9)
            val paint = Paint()
            paint.color = if (light) Color.argb(alpha, 255, 244, 226)
            else Color.argb(alpha, 122, 84, 48)
            canvas.drawRect(x, 0f, x + bandWidth, SIZE.toFloat(), paint)
            x += bandWidth
        }

        // 细纹理：带模糊，避免“竹席”感
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            maskFilter = BlurMaskFilter(2.2f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0 until 90) {
            val gx = random.nextFloat() * SIZE
            grain.strokeWidth = 0.8f + random.nextFloat() * 1.6f
            grain.color = if (random.nextBoolean())
                Color.argb(16 + random.nextInt(14), 255, 246, 230)
            else
                Color.argb(12 + random.nextInt(14), 120, 82, 46)

            val path = Path()
            path.moveTo(gx, -12f)
            var cy = -12f
            var cx = gx
            while (cy < SIZE + 12) {
                cy += 30f
                cx += (random.nextFloat() - 0.5f) * (3f + random.nextFloat() * 4f)
                path.lineTo(cx, cy)
            }
            canvas.drawPath(path, grain)
        }

        // 木节：模糊同心环
        for (i in 0 until 4) {
            val cx = random.nextFloat() * SIZE
            val cy = random.nextFloat() * SIZE
            val baseR = 14f + random.nextFloat() * 20f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.8f
                maskFilter = BlurMaskFilter(2.6f, BlurMaskFilter.Blur.NORMAL)
                color = Color.argb(14, 96, 62, 32)
            }
            for (k in 0 until 4) {
                canvas.drawCircle(cx, cy, baseR + k * 4.5f, ring)
            }
        }

        // 顶部提亮、底部轻微压暗，增加立体感
        val shade = Paint()
        shade.shader = LinearGradient(
            0f, 0f, 0f, SIZE.toFloat(),
            intArrayOf(0x12FFFFFF, 0x00000000, 0x0E000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), shade)

        cached = bmp
        return bmp
    }

    fun shaderPaint(): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}

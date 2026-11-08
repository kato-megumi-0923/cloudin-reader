package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 书架卡片，仿 OPPO 阅读：
 * - 白色圆角卡片 + 细边框
 * - 有封面图就铺满
 * - 没有封面时，卡片内居中排书名，底部标 -TXT- / -EPUB-
 */
class BookCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        val ART = intArrayOf(
            R.drawable.cover_lib_01, R.drawable.cover_lib_02, R.drawable.cover_lib_03,
            R.drawable.cover_lib_04, R.drawable.cover_lib_05, R.drawable.cover_lib_06,
            R.drawable.cover_lib_07, R.drawable.cover_lib_08, R.drawable.cover_lib_09,
            R.drawable.cover_lib_10, R.drawable.cover_lib_11, R.drawable.cover_lib_12,
            R.drawable.cover_lib_13, R.drawable.cover_lib_14, R.drawable.cover_lib_15,
            R.drawable.cover_lib_16, R.drawable.cover_lib_17, R.drawable.cover_lib_18,
            R.drawable.cover_lib_19, R.drawable.cover_lib_20
        )
    }

    private var title: String = ""
    private var bookId: String = ""
    private var format: String = "txt"
    private var bitmap: Bitmap? = null
    private var loadedPath: String? = null

    private val density = resources.displayMetrics.density
    private val radius = 14f * density

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8FFFFFF") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#99FFFFFF")
    }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    fun bind(book: Book) {
        title = book.title
        bookId = book.id
        format = book.format
        if (book.coverPath != loadedPath) {
            loadedPath = book.coverPath
            bitmap = book.coverPath?.let { path ->
                runCatching {
                    BitmapFactory.decodeFile(path)?.let { bmp ->
                        val maxW = 800
                        if (bmp.width > maxW) {
                            Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true)
                        } else bmp
                    }
                }.getOrNull()
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 1.34f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, 0f, w, h)

        val bmp0 = bitmap
        if (bmp0 == null) {
            val (c1, c2) = coverColors()
            coverPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, h, c1, c2, android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, coverPaint)
            coverPaint.shader = null
        } else {
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val bmp = bitmap
        if (bmp != null) {
            canvas.save()
            val path = android.graphics.Path().apply {
                addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(path)
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return
        }

        // 渐变底 + 图库插画（按书名固定分配）
        if (!drawArtwork(canvas, w, h)) drawGirl(canvas, w, h)

        // 底部书名（1–2 行）
        val pad = w * 0.10f
        val maxWidth = w - pad * 2
        textPaint.textSize = (w * 0.082f).coerceAtLeast(8f * density)
        textPaint.color = Color.WHITE
        labelPaint.color = Color.parseColor("#CCFFFFFF")
        textPaint.setShadowLayer(w * 0.02f, 0f, w * 0.008f, 0x55000000)
        val lines = wrap(textPaint, title, maxWidth, 2)
        val lineHeight = textPaint.textSize * 1.28f
        var y = h - h * 0.115f - (lines.size - 1) * lineHeight
        lines.forEach { line ->
            canvas.drawText(line, w / 2f, y, textPaint)
            y += lineHeight
        }

        // 底部格式标签
        labelPaint.textSize = (w * 0.062f).coerceAtLeast(6f * density)
        canvas.drawText("-${format.uppercase()}-", w / 2f, h - h * 0.045f, labelPaint)
    }

    /** 没封面时按书名生成稳定的渐变色，避免整排白卡 */    private fun coverColors(): Pair<Int, Int> {
        val hash = (title.hashCode() and 0x7fffffff) % 360
        val c1 = Color.HSVToColor(floatArrayOf(hash.toFloat(), 0.52f, 0.94f))
        val c2 = Color.HSVToColor(floatArrayOf(((hash + 26) % 360).toFloat(), 0.62f, 0.78f))
        return c1 to c2
    }

    /** 用预置插画做封面：居中裁切 + 底部压暗，保证书名可读 */
    private fun drawArtwork(canvas: Canvas, w: Float, h: Float): Boolean {
        val idx = ((title.hashCode() * 31 + bookId.hashCode()) and 0x7fffffff) % ART.size
        val res = ART[idx]
        val d = runCatching { androidx.core.content.ContextCompat.getDrawable(context, res) }.getOrNull() ?: return false
        val rect = RectF(0f, 0f, w, h)
        canvas.save()
        val path = android.graphics.Path().apply {
            addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)
        val iw = d.intrinsicWidth.coerceAtLeast(1).toFloat()
        val ih = d.intrinsicHeight.coerceAtLeast(1).toFloat()
        val scale = maxOf(w / iw, h / ih)
        val dw = iw * scale
        val dh = ih * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        d.setBounds(
            left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt()
        )
        d.draw(canvas)

        // 底部压暗，托住书名
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
        scrim.shader = android.graphics.LinearGradient(
            0f, h * 0.55f, 0f, h,
            intArrayOf(0x00000000, 0x80000000.toInt(), 0xD9000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.55f, w, h, scrim)
        scrim.shader = null
        canvas.restore()
        return true
    }
    /** 没封面时：渐变底 + 手绘 Q 版少女，按书名换发色 */
    private fun drawGirl(canvas: Canvas, w: Float, h: Float) {
        val hash = (title.hashCode() and 0x7fffffff)
        val hairHue = (hash % 360).toFloat()
        val hairColor = Color.HSVToColor(floatArrayOf(hairHue, 0.35f, 0.42f))
        val hairLight = Color.HSVToColor(floatArrayOf(hairHue, 0.28f, 0.58f))
        val skin = Color.parseColor("#FFE3D3")
        val skinShade = Color.parseColor("#F3C9B4")
        val eyeColor = Color.HSVToColor(floatArrayOf(((hash / 7) % 360).toFloat(), 0.55f, 0.55f))

        val cx = w * 0.5f
        val headR = w * 0.26f
        val headCy = h * 0.40f

        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hairColor }
        val hairLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hairLight }
        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin }
        val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skinShade }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F7FAFF") }
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eyeColor }
        val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FF8FA3") }

        canvas.drawCircle(cx, headCy + headR * 0.15f, headR * 1.30f, hairPaint)
        val hairPath = android.graphics.Path().apply {
            moveTo(cx - headR * 1.25f, headCy)
            quadTo(cx - headR * 1.05f, headCy + headR * 2.6f, cx - headR * 0.55f, headCy + headR * 2.9f)
            lineTo(cx + headR * 0.55f, headCy + headR * 2.9f)
            quadTo(cx + headR * 1.05f, headCy + headR * 2.6f, cx + headR * 1.25f, headCy)
            close()
        }
        canvas.drawPath(hairPath, hairPaint)

        val bodyTop = headCy + headR * 1.05f
        val shoulder = android.graphics.Path().apply {
            moveTo(cx - headR * 1.55f, bodyTop + headR * 1.05f)
            quadTo(cx - headR * 0.75f, bodyTop - headR * 0.18f, cx, bodyTop - headR * 0.05f)
            quadTo(cx + headR * 0.75f, bodyTop - headR * 0.18f, cx + headR * 1.55f, bodyTop + headR * 1.05f)
            close()
        }
        canvas.drawPath(shoulder, whitePaint)
        canvas.drawRect(cx - headR * 0.12f, headCy + headR * 0.72f, cx + headR * 0.12f, bodyTop + headR * 0.15f, shadePaint)

        canvas.drawCircle(cx, headCy, headR, skinPaint)

        val bangs = android.graphics.Path().apply {
            moveTo(cx - headR * 1.02f, headCy - headR * 0.15f)
            cubicTo(
                cx - headR * 0.95f, headCy - headR * 1.25f,
                cx + headR * 0.95f, headCy - headR * 1.25f,
                cx + headR * 1.02f, headCy - headR * 0.15f
            )
            cubicTo(
                cx + headR * 0.55f, headCy - headR * 0.62f,
                cx + headR * 0.18f, headCy - headR * 0.35f,
                cx + headR * 0.02f, headCy - headR * 0.55f
            )
            cubicTo(
                cx - headR * 0.25f, headCy - headR * 0.30f,
                cx - headR * 0.62f, headCy - headR * 0.62f,
                cx - headR * 1.02f, headCy - headR * 0.15f
            )
            close()
        }
        canvas.drawPath(bangs, hairPaint)
        canvas.drawCircle(cx - headR * 0.45f, headCy - headR * 0.62f, headR * 0.12f, hairLightPaint)

        val eyeDx = headR * 0.40f
        val eyeCy = headCy + headR * 0.16f
        val eyeRx = headR * 0.15f
        val eyeRy = headR * 0.20f
        canvas.drawOval(RectF(cx - eyeDx - eyeRx, eyeCy - eyeRy, cx - eyeDx + eyeRx, eyeCy + eyeRy), eyePaint)
        canvas.drawOval(RectF(cx + eyeDx - eyeRx, eyeCy - eyeRy, cx + eyeDx + eyeRx, eyeCy + eyeRy), eyePaint)
        canvas.drawCircle(cx - eyeDx + eyeRx * 0.15f, eyeCy - eyeRy * 0.35f, eyeRx * 0.42f, whitePaint)
        canvas.drawCircle(cx + eyeDx + eyeRx * 0.15f, eyeCy - eyeRy * 0.35f, eyeRx * 0.42f, whitePaint)
        canvas.drawCircle(cx - headR * 0.62f, headCy + headR * 0.42f, headR * 0.14f, blushPaint)
        canvas.drawCircle(cx + headR * 0.62f, headCy + headR * 0.42f, headR * 0.14f, blushPaint)

        val mouth = android.graphics.Path().apply {
            moveTo(cx - headR * 0.10f, headCy + headR * 0.52f)
            quadTo(cx, headCy + headR * 0.62f, cx + headR * 0.10f, headCy + headR * 0.52f)
        }
        val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C2566B")
            style = Paint.Style.STROKE
            strokeWidth = headR * 0.055f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(mouth, mouthPaint)
    }
    private fun wrap(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (paint.measureText(current.toString()) > maxWidth) {
                current.deleteCharAt(current.length - 1)
                if (current.isNotEmpty()) result.add(current.toString())
                current = StringBuilder().append(ch)
            }
            if (result.size >= maxLines) break
        }
        if (current.isNotEmpty() && result.size < maxLines) result.add(current.toString())
        return if (result.isEmpty()) listOf("") else result
    }
}

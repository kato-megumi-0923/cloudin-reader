package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** 书架上的分组文件夹卡片，尺寸与书籍卡片一致。 */
class FolderCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var name = ""
    private var count = 0
    private var isAdd = false

    private val density = resources.displayMetrics.density
    private val radius = 14f * density

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8FFFFFF") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#99FFFFFF")
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E3B77A") }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9A9A9A")
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }

    fun bind(group: BookGroup?, bookCount: Int) {
        isAdd = group == null
        name = group?.name ?: "新建分组"
        count = bookCount
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.34f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val cx = w / 2f
        val iconTop = h * 0.30f
        val iconW = w * 0.42f
        val iconH = iconW * 0.72f
        val left = cx - iconW / 2f
        val right = cx + iconW / 2f

        if (isAdd) {
            val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BFBFBF")
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * density
            }
            canvas.drawRoundRect(
                RectF(left, iconTop, right, iconTop + iconH),
                iconW * 0.14f, iconW * 0.14f, plusPaint
            )
            val cxp = cx
            val cyp = iconTop + iconH / 2f
            canvas.drawLine(cxp - iconW * 0.12f, cyp, cxp + iconW * 0.12f, cyp, plusPaint)
            canvas.drawLine(cxp, cyp - iconW * 0.12f, cxp, cyp + iconW * 0.12f, plusPaint)
        } else {
            drawFolder(canvas, left, iconTop, iconW, iconH)
        }

        namePaint.textSize = (w * 0.10f).coerceAtLeast(10f * density)
        val maxWidth = w * 0.78f
        val lines = wrap(name, maxWidth, 2)
        var y = iconTop + iconH + namePaint.textSize * 1.9f
        lines.forEach { line ->
            canvas.drawText(line, cx, y, namePaint)
            y += namePaint.textSize * 1.35f
        }
        if (!isAdd) {
            canvas.drawText("$count 本", cx, h - h * 0.09f, countPaint)
        }
    }

    private fun drawFolder(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val path = Path()
        val tabH = height * 0.22f
        val r = width * 0.10f
        path.addRoundRect(
            RectF(left, top, left + width * 0.52f, top + tabH * 1.6f),
            r, r, Path.Direction.CW
        )
        canvas.drawPath(path, folderPaint)
        val body = RectF(left, top + tabH * 0.9f, left + width, top + height)
        canvas.drawRoundRect(body, r, r, folderPaint)
        // 文件夹内部高光
        val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#38FFFFFF") }
        canvas.drawRoundRect(
            RectF(left + width * 0.06f, top + tabH * 1.2f, left + width * 0.94f, top + height * 0.92f),
            r * 0.8f, r * 0.8f, lightPaint
        )
    }

    private fun wrap(text: String, maxWidth: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (namePaint.measureText(current.toString()) > maxWidth) {
                current.deleteCharAt(current.length - 1)
                if (current.isNotEmpty()) out.add(current.toString())
                current = StringBuilder().append(ch)
            }
            if (out.size >= maxLines) break
        }
        if (current.isNotEmpty() && out.size < maxLines) out.add(current.toString())
        return if (out.isEmpty()) listOf("") else out
    }
}

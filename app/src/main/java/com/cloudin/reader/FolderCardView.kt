package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** A group preview uses four fixed slots, with its name and count below the card. */
class FolderCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt()
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var showAdd = false
    private val slots = List(4) {
        BookCoverView(context).apply {
            folderPreview = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(this)
        }
    }

    init {
        setWillNotDraw(false)
        background = GradientDrawable().apply {
            setColor(0xE8FFFFFF.toInt())
            cornerRadius = 14f * density
        }
        clipToOutline = true
    }

    fun bind(books: List<Book>, isAdd: Boolean) {
        showAdd = isAdd
        slots.forEachIndexed { index, cover ->
            val book = if (isAdd) null else books.getOrNull(index)
            cover.visibility = if (book == null) View.INVISIBLE else View.VISIBLE
            if (book != null) cover.bind(book)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((w * 1.34f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
        slots.forEachIndexed { index, cover ->
            val tileWidth = if (index % 2 == 0) w / 2 else w - w / 2
            val tileHeight = if (index < 2) h / 2 else h - h / 2
            cover.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        slots.forEachIndexed { index, cover ->
            val left = if (index % 2 == 0) 0 else width / 2
            val top = if (index < 2) 0 else height / 2
            cover.layout(left, top, left + cover.measuredWidth, top + cover.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!showAdd) return
        val x = width / 2f
        val y = height / 2f
        val arm = width * 0.08f
        canvas.drawLine(x - arm, y, x + arm, y, plusPaint)
        canvas.drawLine(x, y - arm, x, y + arm, plusPaint)
    }
}

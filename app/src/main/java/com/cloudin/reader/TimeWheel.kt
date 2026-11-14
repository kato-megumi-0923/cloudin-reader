package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 一列滚轮：选中项居中放大、上下相邻项缩小变淡，选中位上下各一条红色短线。
 *
 * 用 RecyclerView + LinearSnapHelper 而不是系统 NumberPicker —— NumberPicker 的分割线
 * 横穿整列，改不出「短线」效果，字号变化也会触发重新布局导致滚动抖动，这里用缩放代替。
 */
internal class WheelColumn(
    context: Context,
    private val count: Int,
    initial: Int,
    private val dark: Boolean,
    private val formatter: (Int) -> String = { it.toString().padStart(2, '0') }
) : FrameLayout(context) {

    private val itemHeight = context.readerDp(44)
    private val viewHeight = context.readerDp(132)
    private val recycler = RecyclerView(context)
    private val snap = LinearSnapHelper()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8453F.toInt()
    }

    var onChanged: ((Int) -> Unit)? = null

    private val startPosition = initial.coerceIn(0, count - 1)

    var value: Int = startPosition
        private set

    /** 首次定位完成前不回传，否则吸附到 0 的那一下会把外部已选的值改成 0。 */
    private var ready = false

    private val titleColor = SkinTheme.title(dark)
    private val softColor = SkinTheme.soft(dark)

    init {
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = Adapter()
        recycler.setPadding(0, itemHeight, 0, itemHeight)
        recycler.clipToPadding = false
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        recycler.itemAnimator = null
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewHeight))
        snap.attachToRecyclerView(recycler)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                styleChildren()
                val position = snap.findSnapView(rv.layoutManager)?.let { rv.getChildAdapterPosition(it) }
                if (position != null) {
                    val actual = ((position % count) + count) % count
                    if (actual != value) {
                        value = actual
                        if (ready) onChanged?.invoke(value)
                    }
                }
            }
        })
        recycler.post {
            // 用 offset 定位：配合上下 padding，目标项正好落在可视区正中
            // 从中间那一段开始，首尾都有内容，看起来是循环的
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(count + startPosition, 0)
            recycler.post {
                value = startPosition
                styleChildren()
                ready = true
            }
        }
    }

    private fun styleChildren() {
        val center = recycler.height / 2f
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            val childCenter = (child.top + child.bottom) / 2f
            val distance = (childCenter - center) / itemHeight
            val scale = (1f - 0.18f * kotlin.math.abs(distance)).coerceIn(0.72f, 1f)
            child.scaleX = scale
            child.scaleY = scale
            (child as? TextView)?.setTextColor(
                if (kotlin.math.abs(distance) < 0.5f) titleColor else softColor
            )
            child.alpha = (1f - 0.45f * kotlin.math.abs(distance)).coerceIn(0.35f, 1f)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = (width - context.readerDp(52)) / 2f
        val top = (height - itemHeight) / 2f
        val thickness = context.readerDp(1).toFloat()
        canvas.drawRect(inset, top, width - inset, top + thickness, linePaint)
        canvas.drawRect(inset, height - top - thickness, width - inset, height - top, linePaint)
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = count * 3

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val text = TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemHeight
                )
                gravity = Gravity.CENTER
                textSize = 18f
                isClickable = false
            }
            return Holder(text)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            (holder.itemView as TextView).text = formatter(position % count)
        }
    }
}

/** 时/分两列 + 冒号，作为时间选择弹窗的主体。 */
internal fun Context.timeWheelRow(
    initialMinute: Int,
    dark: Boolean,
    onChanged: (minute: Int) -> Unit
): LinearLayout {
    val weekday = readerDp(132)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    var hour = initialMinute / 60
    var minute = initialMinute % 60

    val hourWheel = WheelColumn(this, 24, hour, dark).apply {
        this.onChanged = {
            hour = it
            onChanged(hour * 60 + minute)
        }
    }
    val minuteWheel = WheelColumn(this, 60, minute, dark).apply {
        this.onChanged = {
            minute = it
            onChanged(hour * 60 + minute)
        }
    }

    row.addView(hourWheel, LinearLayout.LayoutParams(readerDp(96), weekday))
    row.addView(TextView(this).apply {
        text = ":"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(SkinTheme.title(dark))
    }, LinearLayout.LayoutParams(readerDp(24), weekday))
    row.addView(minuteWheel, LinearLayout.LayoutParams(readerDp(96), weekday))
    return row
}

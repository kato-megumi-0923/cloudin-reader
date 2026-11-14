package com.cloudin.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 皮肤预览用的书架缩略图：固定 320×380dp 逻辑尺寸，由调用方整体缩放。
 * 皮肤页的卡片把缩放到卡片宽度，预览页直接按逻辑尺寸铺满。
 */
internal class ShelfPreviewLayout(context: Context) : FrameLayout(context) {

    private fun dp(value: Int) = context.readerDp(value)

    private val heading: TextView
    private val toolRow: LinearLayout
    private val grid: LinearLayout
    private val navHost: FrameLayout
    private val navPill: android.view.View
    private val navLabels = mutableListOf<TextView>()

    private val cells = mutableListOf<Cell>()
    private var glassRequested = false
    private var backdrop: View? = null

    private class Cell(val cover: BookCoverView, val label: TextView)

    init {
        setBackgroundColor(Color.TRANSPARENT)

        heading = TextView(context).apply {
            setText("书架")
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(SkinTheme.title(false))
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(heading, LayoutParams(dp(120), dp(26), Gravity.TOP or Gravity.START).apply {
            marginStart = dp(16)
            topMargin = dp(14)
        })

        toolRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(toolRow, LayoutParams(LayoutParams.WRAP_CONTENT, dp(26), Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(16)
            topMargin = dp(14)
        })
        listOf("⌕", "＋").forEach { symbol ->
            val bubble = TextView(context).apply {
                setText(symbol)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(SkinTheme.soft(false))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x3DFFFFFF)
                    setStroke(dp(1), 0x59FFFFFF)
                }
            }
            toolRow.addView(bubble, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginStart = dp(6)
            })
        }

        grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(46)
            bottomMargin = dp(58)
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        navHost = FrameLayout(context)
        addView(navHost, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })

        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0x47FFFFFF)
                setStroke(dp(1), 0x66FFFFFF)
            }
        }
        navHost.addView(pill, LayoutParams(dp(196), dp(34)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navHost.addView(labels, LayoutParams(dp(196), dp(34)))
        listOf("书架", "分组", "喜爱", "我的").forEachIndexed { index, text ->
            val label = TextView(context).apply {
                setText(text)
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(SkinTheme.soft(false))
                if (index == 0) setTypeface(typeface, Typeface.BOLD)
            }
            labels.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            navLabels.add(label)
        }
        navPill = pill

        buildCells()
    }

    private fun buildCells() {
        val rows = 2
        val columns = 3
        for (row in 0 until rows) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            grid.addView(rowView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (row < rows - 1) bottomMargin = dp(10)
            })
            for (column in 0 until columns) {
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                rowView.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (column < columns - 1) marginEnd = dp(8)
                })
                val cover = BookCoverView(context)
                cell.addView(cover, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                val label = TextView(context).apply {
                    textSize = 7.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(SkinTheme.meta(false))
                    setPadding(0, dp(3), 0, 0)
                }
                cell.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(12)))
                cells.add(Cell(cover, label))
            }
        }
    }

    /** 卡片形态用画出来的胶囊，避免每个缩略图都挂一个实时玻璃。 */
    fun usePaintedGlass() {
        glassRequested = false
    }

    /** 预览页换成真正的液态玻璃，背景源为整页背景层。 */
    fun useLiquidGlass(source: View) {
        if (glassRequested && backdrop === source) return
        glassRequested = true
        backdrop = source
        navHost.removeAllViews()
        val glass = AgslGlassView(context).apply {
            cornerRadius = dp(17).toFloat()
            blurRadius = dp(9).toFloat()
            tintColor = Color.WHITE
            tintAlpha = 0.16f
            dispersion = 0.14f
            useControlOptics(17f)
            backdropSource = source
            frosted = GlassStyle.load(context) == GlassStyle.FROSTED
        }
        navHost.addView(glass, LayoutParams(dp(196), dp(34)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navHost.addView(labels, LayoutParams(dp(196), dp(34)))
        navLabels.forEach { label ->
            (label.parent as? android.view.ViewGroup)?.removeView(label)
            labels.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    fun bind(books: List<Book>, dark: Boolean, pale: Boolean = false) {
        heading.setTextColor(SkinTheme.title(dark))
        for (index in 0 until toolRow.childCount) {
            (toolRow.getChildAt(index) as? TextView)?.setTextColor(SkinTheme.soft(dark))
        }
        navLabels.forEach { it.setTextColor(SkinTheme.soft(dark)) }
        (navPill.background as? GradientDrawable)?.setColor(if (dark) 0x33FFFFFF else 0x47FFFFFF)
        cells.forEachIndexed { index, cell ->
            val book = books.getOrNull(index)
            if (book == null) {
                cell.cover.visibility = View.INVISIBLE
                cell.label.text = ""
            } else {
                cell.cover.visibility = View.VISIBLE
                cell.cover.bind(book)
                cell.cover.applySkin(dark, pale)
                cell.label.text = book.title
                cell.label.setTextColor(SkinTheme.meta(dark))
            }
        }
    }

}

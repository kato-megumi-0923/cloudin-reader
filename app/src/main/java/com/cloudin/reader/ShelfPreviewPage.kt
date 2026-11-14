package com.cloudin.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 皮肤预览页里的整页书架示样：按真实书架比例排版（不是把缩略图放大），
 * 标题、封面、玻璃底栏都用真实尺寸，所以放大后文字依然清晰。
 *
 * 底栏玻璃采样 [contentLayer]（背景 + 标题 + 书架内容），和真实书架「整页当背景源」一致；
 * 底栏本身放在 contentLayer 之外，避免 RenderNode 采到自己造成递归。
 */
internal class ShelfPreviewPage(context: Context) : FrameLayout(context) {

    private val contentLayer = FrameLayout(context)
    private val background = View(context)

    private val heading = TextView(context).apply {
        setText("书架")
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(SkinTheme.title(false))
        gravity = Gravity.CENTER_VERTICAL
    }
    private val tools = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val gridHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val navHost = FrameLayout(context)
    private val navLabels = mutableListOf<TextView>()

    private var books: List<Book> = emptyList()
    private var darkSkin = false
    private var paleSkin = false
    private var columns = 0
    private var cellCount = 0
    private val cells = mutableListOf<Cell>()

    private class Cell(val cover: BookCoverView, val label: TextView)

    private fun dp(value: Int) = context.readerDp(value)

    init {
        val margin = dp(22)

        addView(contentLayer, LayoutParams(-1, -1))
        contentLayer.addView(background, LayoutParams(-1, -1))
        contentLayer.addView(heading, LayoutParams(dp(240), dp(56), Gravity.TOP or Gravity.START).apply {
            marginStart = margin
            topMargin = dp(18)
        })
        contentLayer.addView(tools, LayoutParams(LayoutParams.WRAP_CONTENT, dp(56), Gravity.TOP or Gravity.END).apply {
            marginEnd = margin
            topMargin = dp(18)
        })
        listOf("⌕", "＋").forEach { symbol ->
            val bubble = TextView(context).apply {
                setText(symbol)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(SkinTheme.soft(false))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x3DFFFFFF)
                    setStroke(dp(1), 0x59FFFFFF)
                }
            }
            tools.addView(bubble, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(10)
            })
        }
        contentLayer.addView(gridHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(96)
            bottomMargin = dp(150)
            marginStart = margin
            marginEnd = margin
        })

        addView(navHost, LayoutParams(LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(58)
        })
        buildNav()
    }

    /** 换皮肤：只改背景层，底栏玻璃每帧重新采样，会自动跟上。 */
    fun applySkin(skin: Skin) {
        SkinStore.applyTo(context, background, skin)
        darkSkin = skin.dark
        paleSkin = skin.pale
        heading.setTextColor(SkinTheme.title(darkSkin))
        for (index in 0 until tools.childCount) {
            (tools.getChildAt(index) as? TextView)?.setTextColor(SkinTheme.soft(darkSkin))
        }
        navLabels.forEach { it.setTextColor(SkinTheme.soft(darkSkin)) }
        bindCells()
    }

    /** 自选色页面：背景换成纯色。 */
    fun applySolidColor(color: Int) {
        background.background = ColorDrawable(color)
        applySkin(Skin(SkinKind.COLOR, color, dark = Skin.isDarkColor(color)))
    }

    /**
     * 预览示样的底栏：低透明度胶囊。
     * 这里刻意不用 AGSL 玻璃——预览页里它采样到的是偏暗的一块（看起来像不透明色块），
     * 而真书架的底栏在照片上实测几乎是全透的；这里用 15% 白 + 细边更接近真实观感。
     */
    private fun buildNav() {
        navHost.removeAllViews()
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                // 与设计稿一致：胶囊 23% 白，边缘 72% 白
                setColor(0x3BFFFFFF)
                setStroke(dp(1), 0xB8FFFFFF.toInt())
            }
        }
        navHost.addView(pill, LayoutParams(dp(280), dp(56)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navHost.addView(row, LayoutParams(dp(280), dp(56)))
        navLabels.clear()
        listOf("书架", "分组", "喜爱", "我的").forEachIndexed { index, text ->
            val label = TextView(context).apply {
                setText(text)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(SkinTheme.soft(darkSkin))
                if (index == 0) setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            navLabels.add(label)
        }
    }

    fun bind(books: List<Book>, dark: Boolean) {
        this.books = books
        darkSkin = dark
        heading.setTextColor(SkinTheme.title(dark))
        for (index in 0 until tools.childCount) {
            (tools.getChildAt(index) as? TextView)?.setTextColor(SkinTheme.soft(dark))
        }
        navLabels.forEach { it.setTextColor(SkinTheme.soft(dark)) }
        rebuildIfNeeded()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildIfNeeded()
    }

    private fun rebuildIfNeeded() {
        val availableWidth = width - dp(44)
        val availableHeight = height - dp(96) - dp(150)
        if (availableWidth <= 0 || availableHeight <= 0) return
        val gap = dp(14)
        // 列数与真实书架共用 shelfColumns()，格子宽度随列数自适应。
        val nextColumns = shelfColumns(context)
        val actualCell = (availableWidth - gap * (nextColumns - 1)) / nextColumns
        val cellHeight = (actualCell * 1.36f).toInt() + dp(26)
        val maxRows = (availableHeight / (cellHeight + gap)).coerceIn(1, 3)
        // 只生成真实藏书需要的格子：书少就少画，不留空白占位，也不补示例书名。
        val nextCellCount = books.size.coerceAtMost(nextColumns * maxRows)
        if (nextColumns == columns && nextCellCount == cellCount && cells.isNotEmpty()) {
            bindCells()
            return
        }
        columns = nextColumns
        cellCount = nextCellCount
        cells.clear()
        gridHost.removeAllViews()
        val usedRows = ((nextCellCount + nextColumns - 1) / nextColumns).coerceIn(1, maxRows)
        var created = 0
        for (row in 0 until usedRows) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            gridHost.addView(rowView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cellHeight).apply {
                if (row < usedRows - 1) bottomMargin = gap
            })
            for (column in 0 until nextColumns) {
                if (created >= nextCellCount) break
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                rowView.addView(cell, LinearLayout.LayoutParams(actualCell, cellHeight).apply {
                    marginEnd = if (column < nextColumns - 1) gap else 0
                })
                val cover = BookCoverView(context)
                cell.addView(cover, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                val label = TextView(context).apply {
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(SkinTheme.meta(darkSkin))
                    setPadding(0, dp(6), 0, 0)
                }
                cell.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(26)))
                cells.add(Cell(cover, label))
                created++
            }
        }
        bindCells()
    }

    private fun bindCells() {
        cells.forEachIndexed { index, cell ->
            val book = books.getOrNull(index)
            if (book == null) {
                cell.cover.visibility = View.INVISIBLE
                cell.label.text = ""
            } else {
                cell.cover.visibility = View.VISIBLE
                cell.cover.bind(book)
                cell.cover.applySkin(darkSkin, paleSkin)
                cell.label.text = book.title
                cell.label.setTextColor(SkinTheme.meta(darkSkin))
            }
        }
    }
}

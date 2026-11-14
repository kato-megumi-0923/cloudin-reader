package com.cloudin.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.core.view.ViewCompat

/**
 * 设置页：设置项会持续增加，所以独立成页，而不是挤在一张弹窗卡片里。
 *
 * 行 = 标题 + 当前值 + 箭头。带枚举的设置点开弹单选（选完立即生效并刷新右侧值），
 * 独立页面的入口（主题 / 默认封面 / 阅读背景）直接跳转。
 */
class SettingsActivity : BaseActivity() {

    override val systemBarDark: Boolean get() = SkinStore.load(this).dark

    private lateinit var backdrop: View
    private lateinit var pageRoot: View
    private lateinit var list: LinearLayout
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = SkinStore.load(this).dark
        backdrop = View(this).also { SkinStore.applyTo(this, it, SkinStore.load(this)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ink = SkinTheme.title(dark)

        val heading = FrameLayout(this)
        heading.addView(readerLabel("设置", ink, 19f).apply {
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        heading.addView(glassBackButton(ink, backdrop) { finish() },
            FrameLayout.LayoutParams(readerDp(44), readerDp(44), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = readerDp(14) })
        column.addView(heading, LinearLayout.LayoutParams(-1, readerDp(58)))

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(14), readerDp(4), readerDp(14), readerDp(28))
        }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val rootView = FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
        pageRoot = rootView
        setContentView(rootView)
        insetContent(column)
        ViewCompat.requestApplyInsets(column)
    }

    override fun onResume() {
        super.onResume()
        // 从主题 / 默认封面等子页面回来时，右侧的值可能已经变了
        if (::list.isInitialized) rebuild()
    }

    private fun rebuild() {
        dark = SkinStore.load(this).dark
        val skin = SkinStore.load(this)
        list.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = readerDp(22).toFloat()
                setColor(if (dark) 0x2EFFFFFF else 0xF2FFFFFF.toInt())
                setStroke(readerDp(1), if (dark) 0x1FFFFFFF else 0x14000000)
            }
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2))

        addRow(card, "主题", skin.name) { open(SkinActivity::class.java) }
        addRow(card, "默认封面", null) { open(DefaultCoverActivity::class.java) }
        addRow(card, getString(R.string.reader_background), themeName()) {
            open(ReadingBackgroundActivity::class.java)
        }
        addRow(card, getString(R.string.reader_turn_mode), turnName()) { pickTurn() }
        addRow(card, "书架布局", layoutName()) { pickLayout() }
        addRow(card, getString(R.string.profile_glass_style), getString(GlassStyle.load(this).label)) {
            pickGlass()
        }
    }

    private fun open(destination: Class<out android.app.Activity>) {
        startActivity(Intent(this, destination))
    }

    private fun themeName(): String =
        getString(ReaderPrefs.palette(ReaderPrefs.theme(this)).nameRes)

    private fun turnName(): String {
        val current = ReaderPrefs.turnMode(this)
        val label = ReaderPrefs.turns.firstOrNull { it.first == current }?.second
        return label?.let { getString(it) } ?: ""
    }

    private fun layoutName(): String =
        if (shelfPrefs().getString(KEY_LAYOUT, "grid") == "list") "列表" else "网格"

    private fun shelfPrefs() = getSharedPreferences("shelf", MODE_PRIVATE)

    /** 标题 + 右侧当前值 + 箭头；行之间画 1dp 分割线。 */
    private fun addRow(parent: LinearLayout, label: String, value: String?, onClick: () -> Unit) {
        if (parent.childCount > 0) {
            val divider = View(this).apply {
                setBackgroundColor(if (dark) 0x26FFFFFF else 0x11000000)
            }
            parent.addView(divider, LinearLayout.LayoutParams(-1, readerDp(1)).apply {
                marginStart = readerDp(18)
                marginEnd = readerDp(18)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(readerDp(18), 0, readerDp(16), 0)
            background = selectableItemBackground()
            setOnClickListener { onClick() }
        }
        row.addView(readerLabel(label, SkinTheme.title(dark), 15f).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))

        if (!value.isNullOrEmpty()) {
            row.addView(readerLabel(value, SkinTheme.soft(dark), 13f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-2, -1))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_profile_chevron)
            imageTintList = ColorStateList.valueOf(SkinTheme.soft(dark))
            alpha = 0.7f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(readerDp(16), readerDp(16)).apply {
            marginStart = readerDp(8)
        })

        parent.addView(row, LinearLayout.LayoutParams(-1, readerDp(56)))
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        val found = theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return if (found) getDrawable(out.resourceId) else null
    }

    // ---- 单选弹窗 ----

    private fun pickTurn() {
        val current = ReaderPrefs.turnMode(this)
        val modes = ReaderPrefs.turns
        pick(getString(R.string.reader_turn_mode), modes.map { getString(it.second) },
            modes.indexOfFirst { it.first == current }) { index ->
            ReaderPrefs.setTurnMode(this, modes[index].first)
            rebuild()
        }
    }

    private fun pickLayout() {
        val labels = listOf("网格", "列表")
        pick("书架布局", labels, if (layoutName() == "列表") 1 else 0) { index ->
            shelfPrefs().edit()
                .putString(KEY_LAYOUT, if (index == 1) "list" else "grid")
                .apply()
            rebuild()
        }
    }

    private fun pickGlass() {
        val styles = GlassStyle.entries
        pick(getString(R.string.profile_glass_style), styles.map { getString(it.label) },
            GlassStyle.load(this).ordinal) { index ->
            val style = styles[index]
            style.save(this)
            style.applyTo(window.decorView)
            rebuild()
        }
    }

    private fun pick(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val dialog = ComponentDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = title
        val cardRows = content.findViewById<LinearLayout>(R.id.cardRows)
        val ink = SkinTheme.title(dark)
        labels.forEachIndexed { index, label ->
            val row = layoutInflater.inflate(R.layout.row_dialog_action, cardRows, false)
            row.findViewById<TextView>(R.id.cardRowText).apply {
                text = if (index == selected) getString(R.string.checked_choice_label, label) else label
                setTextColor(ink)
            }
            row.setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
            cardRows.addView(row)
        }
        content.findViewById<TextView>(R.id.cardTitle).setTextColor(SkinTheme.title(dark))
        // 单选：点选项即生效，点卡片外面即可关闭，不再放「取消」按钮
        content.findViewById<View>(R.id.cardCancel).visibility = View.GONE
        // 没有底部按钮时给列表留一点下边距，免得最后一行贴着圆角
        val cardRowsHost = content.findViewById<View>(R.id.cardRows)
        cardRowsHost.setPadding(0, 0, 0, readerDp(10))

        // 玻璃卡片：背景源取本页根视图（与书架弹窗同一模式）。content 只留一层很淡的
        // 白蒙版保证文字可读，真正的模糊/着色由 AGSL 画。
        content.background = GradientDrawable().apply {
            cornerRadius = readerDp(26).toFloat()
            setColor(0x2EFFFFFF)
        }
        val skin = SkinStore.load(this)
        val glass = AgslGlassView(this).apply {
            cornerRadius = readerDp(26).toFloat()
            blurRadius = readerDp(11).toFloat()
            tintColor = if (skin.pale) 0xFFDDE4ED.toInt() else Color.WHITE
            tintAlpha = if (skin.pale) 0.46f else 0.10f
            dispersion = 0.16f
            usePopupOptics()
        }
        glass.addView(content, FrameLayout.LayoutParams(-1, -2))
        dialog.setContentView(glass)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (dark) 0.20f else 0.06f)
        }
        dialog.show()
        val width = minOf(
            readerDp(345),
            (resources.displayMetrics.widthPixels - readerDp(32)).coerceAtLeast(1)
        )
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        glass.backdropSource = pageRoot
        GlassStyle.load(this).applyTo(glass)
    }

    private companion object {
        const val KEY_LAYOUT = "shelf_layout"
    }
}

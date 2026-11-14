package com.cloudin.reader

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat

/**
 * 设置主页。设置项会持续增加，所以是独立页面；每行 = 标题 / 当前值 / 箭头。
 * 阅读背景、翻页方式收进「阅读设置」，深色相关收进「夜间模式」。
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

        pageRoot = FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(pageRoot)
        insetContent(column)
        ViewCompat.requestApplyInsets(column)
    }

    override fun onResume() {
        super.onResume()
        // 从子页面回来时右侧的值可能已经变了
        if (::list.isInitialized) rebuild()
    }

    private fun rebuild() {
        dark = SkinStore.load(this).dark
        list.removeAllViews()
        val card = settingCard(dark)
        card.addSettingRow(dark, "主题", SkinStore.load(this).name) {
            open(SkinActivity::class.java)
        }
        card.addSettingRow(dark, "阅读设置") {
            open(ReadingSettingsActivity::class.java)
        }
        card.addSettingRow(dark, "夜间模式", NightMode.label(this)) {
            open(NightModeActivity::class.java)
        }
        card.addSettingRow(dark, "默认封面") {
            open(DefaultCoverActivity::class.java)
        }
        card.addSettingRow(dark, "书架布局", layoutName()) { pickLayout() }
        card.addSettingRow(
            dark, getString(R.string.profile_glass_style),
            getString(GlassStyle.load(this).label)
        ) { pickGlass() }
        list.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun open(destination: Class<out android.app.Activity>) {
        startActivity(Intent(this, destination))
    }

    private fun layoutName(): String =
        if (shelfPrefs().getString(KEY_LAYOUT, "grid") == "list") "列表" else "网格"

    private fun shelfPrefs() = getSharedPreferences("shelf", MODE_PRIVATE)

    private fun pickLayout() {
        val labels = listOf("网格", "列表")
        showSettingChoice(dark, pageRoot, "书架布局", labels, if (layoutName() == "列表") 1 else 0) { index ->
            shelfPrefs().edit()
                .putString(KEY_LAYOUT, if (index == 1) "list" else "grid")
                .apply()
            rebuild()
        }
    }

    private fun pickGlass() {
        val styles = GlassStyle.entries
        showSettingChoice(
            dark, pageRoot, getString(R.string.profile_glass_style),
            styles.map { getString(it.label) }, GlassStyle.load(this).ordinal
        ) { index ->
            val style = styles[index]
            style.save(this)
            style.applyTo(window.decorView)
            rebuild()
        }
    }

    private companion object {
        const val KEY_LAYOUT = "shelf_layout"
    }
}

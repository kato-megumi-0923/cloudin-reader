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

/** 阅读设置：阅读背景与翻页方式收在这一页，设置主页只留入口。 */
class ReadingSettingsActivity : BaseActivity() {

    override val systemBarDark: Boolean get() = SkinStore.load(this).dark

    override val reloadsOnSkinChange: Boolean get() = true

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
        heading.addView(readerLabel("阅读设置", ink, 19f).apply {
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
        if (!::list.isInitialized) return
        if (SkinStore.load(this).dark != dark) {
            recreate()
            return
        }
        rebuild()
    }

    private fun rebuild() {
        dark = SkinStore.load(this).dark
        list.removeAllViews()
        val card = settingCard(dark)
        card.addSettingRow(dark, getString(R.string.reader_background), themeName()) {
            startActivity(Intent(this, ReadingBackgroundActivity::class.java))
        }
        card.addSettingRow(dark, getString(R.string.reader_turn_mode), turnName()) { pickTurn() }
        list.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun themeName(): String =
        getString(ReaderPrefs.palette(ReaderPrefs.theme(this)).nameRes)

    private fun turnName(): String {
        val current = ReaderPrefs.turnMode(this)
        val label = ReaderPrefs.turns.firstOrNull { it.first == current }?.second
        return label?.let { getString(it) } ?: ""
    }

    private fun pickTurn() {
        val current = ReaderPrefs.turnMode(this)
        val modes = ReaderPrefs.turns
        showSettingChoice(
            dark, pageRoot, getString(R.string.reader_turn_mode),
            modes.map { getString(it.second) },
            modes.indexOfFirst { it.first == current }
        ) { index ->
            ReaderPrefs.setTurnMode(this, modes[index].first)
            rebuild()
        }
    }
}

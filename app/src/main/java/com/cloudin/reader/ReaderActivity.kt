package com.cloudin.reader

import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity

class ReaderActivity : AppCompatActivity() {

    private enum class Panel { SETTINGS, FONT, TURN, PROGRESS, AUTO, DISPLAY, MORE }

    private lateinit var book: Book
    private lateinit var pageView: PageView
    private lateinit var topArea: View
    private lateinit var bottomArea: View
    private lateinit var topBar: View
    private lateinit var backButton: GlassActionButton
    private lateinit var menuButton: GlassActionButton

    // 搜索态
    private var searchQuery: String? = null
    private var searchMatches: List<Int> = emptyList()
    private var searchIndex = 0
    private var searchReturnOffset = 0
    private var searchTopChip: View? = null
    private var searchNavBar: View? = null
    private var searchLabel: TextView? = null
    private var searchCounter: TextView? = null
    private lateinit var settingsPanel: AgslGlassView
    private var panelSection: Panel? = null
    private var panelHeightAnimator: ValueAnimator? = null
    private lateinit var progressControls: View
    private lateinit var loading: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtChapter: TextView
    private lateinit var txtProgress: TextView
    private lateinit var seekBar: SeekBar
    private var document: Document? = null
    private var theme = ReaderPrefs.THEME_PAPER
    private var lastProgress = 0f
    private var barsVisible = false
    private var readerResumed = false
    private var readingStartedAt: Long? = null
    private var restoredOffset: Int? = null
    private var autoReading = false
    private var autoFrameNanos = 0L
    private var autoPendingPixels = 0f
    private var autoPixelsPerSecond = 0f
    private val autoTick = Choreographer.FrameCallback(::advanceAutomaticReading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredOffset = savedInstanceState?.getInt("readingOffset", -1)?.takeIf { it >= 0 }
        autoReading = savedInstanceState?.getBoolean("autoReading") ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_reader)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (barsVisible) toggleBars() else finish()
            }
        })

        val rootView = findViewById<View>(R.id.root)
        val top = findViewById<View>(R.id.topArea)
        val bottom = findViewById<View>(R.id.bottomArea)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            top.setPadding(top.paddingLeft, bars.top, top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bars.bottom)
            findViewById<PageView>(R.id.pageView).setBottomInset(bars.bottom)
            findViewById<PageView>(R.id.pageView).setTopInset(bars.top)
            insets
        }

        val bookId = intent.getStringExtra("bookId")
        val found = BookStore.load(this).firstOrNull { it.id == bookId }
        if (found == null) {
            finish()
            return
        }
        book = found

        pageView = findViewById(R.id.pageView)
        topArea = findViewById(R.id.topArea)
        bottomArea = findViewById(R.id.bottomArea)
        topBar = findViewById(R.id.topBar)
        backButton = findViewById(R.id.backBar)
        menuButton = findViewById(R.id.btnMenu)
        settingsPanel = findViewById(R.id.settingsPanel)
        rootView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val width = minOf(dp(if (r - l > b - t) 500 else 470), (r - l - dp(28)).coerceAtLeast(1))
                settingsPanel.layoutParams = settingsPanel.layoutParams.apply { this.width = width }
                if (panelSection == Panel.SETTINGS) showSettings(Panel.SETTINGS, refresh = true)
            }
        }
        progressControls = layoutInflater.inflate(R.layout.reader_progress_controls, settingsPanel, false)
        loading = findViewById(R.id.loading)
        txtTitle = findViewById(R.id.txtTitle)
        txtChapter = findViewById(R.id.txtChapter)
        txtProgress = progressControls.findViewById(R.id.txtProgress)
        seekBar = progressControls.findViewById(R.id.seekBar)
        txtTitle.text = book.title

        theme = ReaderPrefs.theme(this)
        pageView.setTheme(theme)
        pageView.setFontSize(ReaderPrefs.fontSize(this).toFloat())
        pageView.setFontFamily(ReaderPrefs.fontFamily(this))
        pageView.setMargin(ReaderPrefs.margin(this))
        pageView.setLineSpacing(ReaderPrefs.lineSpacing(this))
        applyLayoutPreferences()
        pageView.setShowFooter(ReaderPrefs.showFooter(this))
        pageView.setShowChapter(ReaderPrefs.showChapter(this))
        applyBrightness()
        applyChromeColors()

        backButton.configureBackButton(
            ReaderPrefs.ink(theme), pageView, ReaderPrefs.background(theme),
            controlRadiusDp = 23f, iconSizeDp = 24
        ) { finish() }
        menuButton.run {
            text = "···"
            textView.textSize = 15f
            contentDescription = getString(R.string.reader_book_info)
            useControlOptics(23f)
            backdropSource = pageView
            frosted = GlassStyle.load(this@ReaderActivity) == GlassStyle.FROSTED
            setGlassClickListener { showBookInfo() }
        }
        setupBottomTabs()

        pageView.onCenterTap = { toggleBars() }
        pageView.onPageChanged = { _, _, progress ->
            lastProgress = progress
            updateProgressUi(progress)
        }
        pageView.onChapterChanged = { chapter -> if (txtChapter.text != chapter) txtChapter.text = chapter }
        pageView.onImageTap = { image ->
            startActivity(Intent(this, ReaderImageActivity::class.java)
                .putExtra("bookId", book.id).putExtra("imagePath", image.path))
        }
        listOf(
            R.id.topBar to 22f,
            R.id.bottomBar to 27f,
            R.id.settingsPanel to 24f
        ).forEach { (id, radiusDp) ->
            findViewById<AgslGlassView>(id).apply {
                backdropSource = pageView
                useControlOptics(radiusDp)
                tintColor = ReaderPrefs.background(theme)
            }
        }
        settingsPanel.usePopupOptics()
        GlassStyle.load(this).applyTo(rootView)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val doc = document ?: return
                pageView.seekToOffset((progress / 1000f * doc.text.length).toInt())
                txtProgress.text = "${progress / 10}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveProgress(lastProgress)
            }
        })

        loadDocument()
    }

    private fun loadDocument() {
        loading.visibility = View.VISIBLE
        Thread {
            val doc = runCatching { DocumentLoader.load(book) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (doc == null) {
                    showBookError()
                    return@runOnUiThread
                }
                val start = restoredOffset ?: book.readingOffset ?: (book.progress * doc.text.length).toInt()
                pageView.openDocument(doc, start, book.id) { ready ->
                    if (isFinishing || isDestroyed) return@openDocument
                    loading.visibility = View.GONE
                    if (!ready) {
                        showBookError()
                        return@openDocument
                    }
                    document = doc
                    if (readerResumed) readingStartedAt = SystemClock.elapsedRealtime()
                    txtChapter.text = doc.chapterAt(start)
                    updateProgressUi(lastProgress)
                    scheduleAutoReading()
                }
            }
        }.start()
    }

    private fun showBookError() {
        loading.visibility = View.GONE
        showGlassNotice(
            getString(R.string.reader_book_error),
            getString(R.string.reader_book_error_detail),
            getString(R.string.ok)
        ) { finish() }
    }

    private fun updateProgressUi(progress: Float) {
        val p = (progress * 1000).toInt().coerceIn(0, 1000)
        if (seekBar.progress != p) seekBar.progress = p
        val label = "${(progress * 100).toInt()}%"
        if (txtProgress.text != label) txtProgress.text = label
    }

    private fun applyChromeColors() {
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        topBar.setBackgroundColor(Color.TRANSPARENT)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val dark = ReaderPrefs.palette(theme).dark
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        txtTitle.setTextColor(ink)
        txtChapter.setTextColor(soft)
        txtChapter.visibility = if (ReaderPrefs.showChapter(this)) View.VISIBLE else View.GONE
        txtProgress.setTextColor(soft)
        backButton.setBackIconColor(ink)
        if (searchTopChip != null) styleSearchChrome()
        menuButton.textView.setTextColor(ink)
        menuButton.tintColor = ReaderPrefs.chrome(theme)
        val sliderTint = ColorStateList.valueOf(ink)
        seekBar.progressTintList = sliderTint
        seekBar.thumbTintList = sliderTint
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(soft)
        loading.setTextColor(soft)
        findViewById<View>(R.id.root).setBackgroundColor(ReaderPrefs.background(theme))
        listOf(R.id.topBar, R.id.backBar, R.id.bottomBar, R.id.settingsPanel).forEach { id ->
            findViewById<AgslGlassView>(id).tintColor = ReaderPrefs.chrome(theme)
        }
        settingsPanel.tintAlpha = if (dark) 0.62f else 0.48f
        val toolbar = findViewById<ViewGroup>(R.id.bottomBar)
        val row = toolbar.getChildAt(0) as LinearLayout
        for (index in 0 until row.childCount) {
            val item = row.getChildAt(index) as LinearLayout
            (item.getChildAt(0) as TextView).setTextColor(ink)
            (item.getChildAt(1) as TextView).setTextColor(soft)
        }
        val night = toolbar.findViewById<LinearLayout>(R.id.tabNight)
        (night.getChildAt(0) as TextView).text = if (dark) "☼" else "☾"
        val nightLabel = getString(if (dark) R.string.reader_theme_day else R.string.reader_theme_night)
        (night.getChildAt(1) as TextView).text = nightLabel
        night.contentDescription = nightLabel
        val selection = if (dark) 0x20FFFFFF else 0x1C5F441F
        val activeTab = when (panelSection) {
            null -> View.NO_ID
            Panel.PROGRESS -> R.id.tabProgress
            else -> R.id.tabSettings
        }
        listOf(R.id.tabProgress, R.id.tabSettings).forEach { id ->
            toolbar.findViewById<View>(id).apply {
                isSelected = id == activeTab
                background = if (isSelected) readerPill(selection) else null
            }
        }
    }

    private fun setupBottomTabs() {
        val actions: List<Pair<Int, () -> Unit>> = listOf(
            R.id.tabCatalog to { showCatalog() },
            R.id.tabProgress to { showSettings(Panel.PROGRESS) },
            R.id.tabNight to {
                changeTheme(if (ReaderPrefs.palette(theme).dark) ReaderPrefs.dayTheme(this) else ReaderPrefs.THEME_NIGHT)
            },
            R.id.tabSettings to { showSettings(Panel.SETTINGS) }
        )
        val toolbar = findViewById<ViewGroup>(R.id.bottomBar)
        actions.forEach { (id, action) ->
            findViewById<LinearLayout>(id).apply {
                contentDescription = (getChildAt(1) as TextView).text
                isFocusable = true
                setGlassClickListener(toolbar) { action() }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toggleBars() {
        // 搜索态用自带的顶部/底部条，不再叠加常规工具栏
        if (searchQuery != null) return
        barsVisible = !barsVisible
        pageView.setChromeVisible(barsVisible)
        if (!barsVisible) hideSettingsPanel()
        scheduleAutoReading()
        animateBar(topArea, barsVisible)
        animateBar(bottomArea, barsVisible)
    }

    private fun hideNormalChromeForSearch() {
        if (searchQuery == null) return
        barsVisible = false
        pageView.setChromeVisible(false)
        listOf(topArea, bottomArea, settingsPanel).forEach { it.visibility = View.GONE }
    }

    private fun animateBar(view: View, show: Boolean) {
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    private fun showSettings(section: Panel, refresh: Boolean = false) {
        if (panelSection == section && !refresh) {
            hideSettingsPanel()
            return
        }
        val opening = panelSection == null
        val previousHeight = settingsPanel.height
        stopPanelHeightAnimation()
        settingsPanel.animate().cancel()
        panelSection = section
        applyChromeColors()
        val content = when (section) {
            Panel.SETTINGS -> mainSettings()
            Panel.FONT -> fontSettings()
            Panel.TURN -> sectionPanel(getString(R.string.reader_turn_mode)) {
                addView(readerTurnOptions(theme, pageView) { applyLayoutPreferences() })
            }
            Panel.PROGRESS -> sectionPanel(getString(R.string.reader_progress), main = true) {
                (progressControls.parent as? ViewGroup)?.removeView(progressControls)
                addView(progressControls, LinearLayout.LayoutParams(-1, -2))
            }
            Panel.AUTO -> sectionPanel(getString(R.string.reader_auto)) {
                val speed = slider(getString(R.string.reader_auto_speed), ReaderPrefs.autoSpeed(this@ReaderActivity), 10, 90) {
                    ReaderPrefs.setAutoSpeed(this@ReaderActivity, it)
                    scheduleAutoReading()
                }
                addView(speed, rowParams())
                addView(panelButton(if (autoReading) getString(R.string.reader_auto_stop) else getString(R.string.reader_auto_start)) {
                    autoReading = !autoReading
                    applyLayoutPreferences()
                    if (autoReading && barsVisible) toggleBars() else showSettings(Panel.SETTINGS)
                    scheduleAutoReading()
                }, rowParams())
            }
            Panel.DISPLAY -> sectionPanel(getString(R.string.reader_display_settings)) {
                addView(settingCheck(getString(R.string.reader_show_chapter), ReaderPrefs.showChapter(this@ReaderActivity)) {
                    ReaderPrefs.setShowChapter(this@ReaderActivity, it)
                    pageView.setShowChapter(it)
                    applyChromeColors()
                }, rowParams())
                addView(settingCheck(getString(R.string.reader_show_footer), ReaderPrefs.showFooter(this@ReaderActivity)) {
                    ReaderPrefs.setShowFooter(this@ReaderActivity, it)
                    pageView.setShowFooter(it)
                }, rowParams())
            }
            Panel.MORE -> sectionPanel(getString(R.string.reader_more_settings)) {
                addView(settingCheck(getString(R.string.reader_volume_turns), ReaderPrefs.volumeTurns(this@ReaderActivity)) {
                    ReaderPrefs.setVolumeTurns(this@ReaderActivity, it)
                }, rowParams())
                addView(panelButton(getString(R.string.reader_submenu_label, getString(R.string.reader_progress)), R.id.readerSettingProgress) { showSettings(Panel.PROGRESS) }, rowParams())
            }
        }
        settingsPanel.removeAllViews()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val safeHeight = (pageView.height * 0.62f).toInt().coerceAtLeast(dp(120))
                val maxHeight = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) safeHeight
                    else minOf(safeHeight, MeasureSpec.getSize(heightSpec))
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        settingsPanel.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.translationY = if (opening) dp(22).toFloat() else 0f
        scroll.alpha = if (refresh) 1f else 0f
        scroll.translationX = if (opening || refresh) 0f else dp(18).toFloat() * if (section == Panel.SETTINGS) -1 else 1
        if (!opening && !refresh && previousHeight > 0) {
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (settingsPanel.layoutParams.width.takeIf { it > 0 } ?: settingsPanel.width).coerceAtLeast(1),
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = scroll.measuredHeight
            if (targetHeight != previousHeight) {
                settingsPanel.layoutParams = settingsPanel.layoutParams.apply { height = previousHeight }
                panelHeightAnimator = ValueAnimator.ofInt(previousHeight, targetHeight).apply {
                    duration = 220L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { frame ->
                        settingsPanel.layoutParams = settingsPanel.layoutParams.apply { height = frame.animatedValue as Int }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            settingsPanel.layoutParams = settingsPanel.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                            panelHeightAnimator = null
                        }
                    })
                    start()
                }
            }
        }
        settingsPanel.animate().translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        scroll.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun mainSettings() = sectionPanel(getString(R.string.reader_settings), main = true) {
        val followSystem = ReaderPrefs.followsBrightness(this@ReaderActivity)
        val brightness = slider(getString(R.string.reader_brightness), ReaderPrefs.brightness(this@ReaderActivity), 1, 100, "%") {
            ReaderPrefs.setBrightness(this@ReaderActivity, it)
            applyBrightness()
        }
        val level = brightness.getChildAt(1)
        val levelText = brightness.getChildAt(2) as TextView
        fun updateBrightnessState(checked: Boolean) {
            level.isEnabled = !checked
            levelText.text = if (checked) getString(R.string.reader_system) else "${ReaderPrefs.brightness(this@ReaderActivity)}%"
        }
        brightness.addView(settingCheck(getString(R.string.reader_follow_system), followSystem) {
            ReaderPrefs.setFollowsBrightness(this@ReaderActivity, it)
            updateBrightnessState(it)
            applyBrightness()
        })
        updateBrightnessState(followSystem)
        addView(brightness, rowParams())

        val fontRow = controlRow()
        fontRow.addView(readerLabel(getString(R.string.reader_font_size), ReaderPrefs.softInk(theme), 13f), LinearLayout.LayoutParams(dp(44), -1))
        val stepper = controlRow().apply {
            background = readerPill(if (ReaderPrefs.palette(theme).dark) 0x20FFFFFF else 0x1B473315)
        }
        val sizeLabel = readerLabel(ReaderPrefs.fontSize(this@ReaderActivity).toString(), ReaderPrefs.ink(theme), 15f)
        fun changeFont(delta: Int) {
            val size = (ReaderPrefs.fontSize(this@ReaderActivity) + delta).coerceIn(14, 32)
            ReaderPrefs.setFontSize(this@ReaderActivity, size)
            pageView.setFontSize(size.toFloat())
            sizeLabel.text = size.toString()
        }
        stepper.addView(panelButton("−") { changeFont(-1) }, weighted())
        stepper.addView(sizeLabel, weighted())
        stepper.addView(panelButton("＋") { changeFont(1) }, weighted())
        fontRow.addView(stepper, weighted())
        fontRow.addView(panelButton(getString(R.string.reader_submenu_label, getString(R.string.reader_font_settings)), R.id.readerSettingFont) { showSettings(Panel.FONT) },
            weighted().apply { marginStart = dp(8) })
        addView(fontRow, rowParams())

        val themes = listOf(ReaderPrefs.THEME_PURE_WHITE, ReaderPrefs.THEME_NIGHT, ReaderPrefs.THEME_BLACK,
            ReaderPrefs.THEME_GREEN, ReaderPrefs.THEME_PAPER, ReaderPrefs.THEME_WOOD)
        val narrow = settingsPanel.layoutParams.width in 1 until dp(400)
        val swatchRow = controlRow()
        themes.forEach { id ->
            val frame = FrameLayout(this@ReaderActivity)
            val palette = ReaderPrefs.palette(id)
            val selected = id == theme
            val swatch = readerLabel(if (selected) "✓" else "", palette.ink, 16f).apply {
                contentDescription = if (selected) getString(R.string.reader_theme_selected, getString(palette.nameRes))
                    else getString(palette.nameRes)
                isSelected = selected
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.paper)
                    setStroke(dp(if (selected) 2 else 1), if (selected) ReaderPrefs.ink(theme) else 0x33444444)
                }
            }
            frame.addView(swatch, FrameLayout.LayoutParams(dp(if (narrow) 32 else 38), dp(if (narrow) 32 else 38), Gravity.CENTER))
            frame.setGlassClickListener(swatch) {
                changeTheme(id)
            }
            frame.contentDescription = swatch.contentDescription
            swatch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            swatchRow.addView(frame, weighted())
        }
        val gallery = panelButton(getString(R.string.reader_submenu_label, getString(R.string.reader_more_backgrounds))) {
            startActivity(Intent(this@ReaderActivity, ReadingBackgroundActivity::class.java))
        }
        if (!narrow) swatchRow.addView(gallery, LinearLayout.LayoutParams(dp(94), -1))
        addView(swatchRow, rowParams())
        if (narrow) addView(gallery, rowParams())

        val actions = listOf(
            panelButton(getString(R.string.reader_auto)) { showSettings(Panel.AUTO) },
            panelButton(getString(R.string.reader_turn_mode), R.id.readerSettingTurn) { showSettings(Panel.TURN) },
            panelButton(getString(R.string.reader_display_settings)) { showSettings(Panel.DISPLAY) },
            panelButton(getString(R.string.reader_more_settings), R.id.readerSettingMore) { showSettings(Panel.MORE) }
        )
        actions.chunked(if (narrow) 2 else 4).forEach { buttons ->
            val row = controlRow()
            buttons.forEachIndexed { index, button ->
                row.addView(button, weighted().apply { if (index > 0) marginStart = dp(6) })
            }
            addView(row, rowParams())
        }
    }

    private fun fontSettings() = sectionPanel(getString(R.string.reader_font_settings)) {
        val fonts = ReaderPrefs.fonts
        addView(readerSegments(fonts.map { getString(it.second) }, fonts.indexOfFirst {
            it.first == ReaderPrefs.fontFamily(this@ReaderActivity)
        }, theme, pageView, getString(R.string.reader_font)) {
            val family = fonts[it].first
            ReaderPrefs.setFontFamily(this@ReaderActivity, family)
            pageView.setFontFamily(family)
        }, rowParams())
        addView(slider(getString(R.string.reader_font_size), ReaderPrefs.fontSize(this@ReaderActivity), 14, 32) {
            ReaderPrefs.setFontSize(this@ReaderActivity, it)
            pageView.setFontSize(it.toFloat())
        }, rowParams())
        addView(slider(getString(R.string.reader_margin), ReaderPrefs.margin(this@ReaderActivity), 0, 100) {
            ReaderPrefs.setMargin(this@ReaderActivity, it)
            pageView.setMargin(it)
        }, rowParams())
        addView(slider(getString(R.string.reader_line_spacing), ReaderPrefs.lineSpacing(this@ReaderActivity), 0, 100) {
            ReaderPrefs.setLineSpacing(this@ReaderActivity, it)
            pageView.setLineSpacing(it)
        }, rowParams())
    }

    private fun sectionPanel(title: String, main: Boolean = false, populate: LinearLayout.() -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            val heading = FrameLayout(this@ReaderActivity)
            heading.addView(readerLabel(title, ReaderPrefs.ink(theme), 16f).apply {
                setTypeface(null, Typeface.BOLD)
                if (main) gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, FrameLayout.LayoutParams(-1, -1))
            if (!main) {
                val back = glassBackButton(ReaderPrefs.ink(theme), pageView, ReaderPrefs.chrome(theme)) {
                    showSettings(Panel.SETTINGS)
                }.apply {
                    contentDescription = getString(R.string.reader_back_to_settings)
                }
                heading.addView(back, FrameLayout.LayoutParams(dp(44), dp(44),
                    Gravity.START or Gravity.CENTER_VERTICAL))
            }
            addView(heading, LinearLayout.LayoutParams(-1, dp(46)))
            populate()
        }

    private fun controlRow() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) }
    private fun weighted() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun panelButton(label: String, id: Int = View.NO_ID, action: () -> Unit) =
        readerButton(label, theme, pageView, action).apply { this.id = id }

    private fun settingCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 13f
        setTextColor(ReaderPrefs.ink(theme))
        buttonTintList = ColorStateList.valueOf(ReaderPrefs.ink(theme))
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun slider(label: String, value: Int, min: Int, max: Int, suffix: String = "",
                       onChange: (Int) -> Unit): LinearLayout {
        val row = controlRow()
        row.addView(readerLabel(label, ReaderPrefs.softInk(theme), 13f), LinearLayout.LayoutParams(dp(44), -1))
        val valueLabel = readerLabel("$value$suffix", ReaderPrefs.ink(theme), 13f)
        val slider = SeekBar(this).apply {
            contentDescription = label
            this.max = max - min
            progress = value - min
            val tint = ColorStateList.valueOf(ReaderPrefs.ink(theme))
            progressTintList = tint
            thumbTintList = tint
            progressBackgroundTintList = ColorStateList.valueOf(ReaderPrefs.softInk(theme))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        valueLabel.text = "${progress + min}$suffix"
                        onChange(progress + min)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        row.addView(slider, weighted())
        row.addView(valueLabel, LinearLayout.LayoutParams(dp(44), -1))
        return row
    }

    private fun stopPanelHeightAnimation() {
        panelHeightAnimator?.removeAllListeners()
        panelHeightAnimator?.cancel()
        panelHeightAnimator = null
        settingsPanel.layoutParams = settingsPanel.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    private fun hideSettingsPanel() {
        if (panelSection == null) return
        panelSection = null
        applyChromeColors()
        stopPanelHeightAnimation()
        settingsPanel.animate().cancel()
        settingsPanel.animate().translationY(settingsPanel.height.toFloat()).setDuration(140)
            .withEndAction {
                settingsPanel.visibility = View.GONE
                settingsPanel.removeAllViews()
            }.start()
    }
    private fun showGlassNotice(
        title: String,
        message: String?,
        okText: String?,
        rows: List<String> = emptyList(),
        highlightIndex: Int = -1,
        showBack: Boolean = false,
        onPick: ((Int) -> Unit)? = null
    ) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        val ink = ReaderPrefs.ink(theme)
        content.findViewById<TextView>(R.id.cardTitle).apply {
            text = title
            setTextColor(ink)
        }
        if (!message.isNullOrEmpty()) {
            val m = content.findViewById<TextView>(R.id.cardMessage)
            m.text = message
            m.setTextColor(ReaderPrefs.softInk(theme))
            m.visibility = View.VISIBLE
        }
        val container = content.findViewById<LinearLayout>(R.id.cardRows)
        val chapterScroll = container.parent as android.widget.ScrollView
        val rowViews = ArrayList<TextView>(rows.size)
        rows.forEachIndexed { index, label ->
            val tv = TextView(this)
            tv.text = label
            tv.gravity = Gravity.CENTER
            tv.textSize = 15f
            // 当前章标红加粗，一眼能看出读到哪
            if (index == highlightIndex) {
                tv.setTextColor(getColor(R.color.shelf_tab_selected))
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(ink)
            }
            val v = (14 * resources.displayMetrics.density).toInt()
            tv.setPadding(v, v, v, v)
            tv.isClickable = true
            tv.setGlassClickListener {
                dialog.dismiss()
                onPick?.invoke(index)
            }
            container.addView(tv)
            rowViews.add(tv)
        }
        val cancel = content.findViewById<GlassActionButton>(R.id.cardCancel)
        cancel.textView.setTextColor(ink)
        cancel.tintColor = ReaderPrefs.chrome(theme)
        if (!okText.isNullOrEmpty()) {
            cancel.text = okText
            cancel.setGlassClickListener {
                dialog.dismiss()
                onPick?.invoke(-1)
            }
        } else {
            cancel.text = getString(R.string.cancel)
            cancel.setGlassClickListener { dialog.dismiss() }
        }
        if (showBack) {
            // 目录这类长列表用左上角返回，底部取消按钮去掉，省一行高度
            cancel.visibility = View.GONE
            val back = content.findViewById<GlassActionButton>(R.id.cardBack)
            back.visibility = View.VISIBLE
            back.useBackIcon(ink)
            back.tintColor = ReaderPrefs.chrome(theme)
            back.setGlassClickListener { dialog.dismiss() }
            val bottom = (16 * resources.displayMetrics.density).toInt()
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
        }
        content.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 26 * resources.displayMetrics.density
            setColor((ReaderPrefs.chrome(theme) and 0xFFFFFF) or 0xB8000000.toInt())
        }
        val availableWidth = pageView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableHeight = pageView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val dialogWidth = minOf(dp(345), (availableWidth - dp(32)).coerceAtLeast(1))
        val maxHeight = (availableHeight * 0.66f).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val fixedHeight = content.measuredHeight - chapterScroll.measuredHeight
        val maxListHeight = (maxHeight - fixedHeight).coerceAtLeast(0)
        if (chapterScroll.measuredHeight > maxListHeight) {
            chapterScroll.layoutParams.height = maxListHeight
        }

        val glass = AgslGlassView(this).apply {
            useControlOptics(26f)
            usePopupOptics()
            backdropSource = pageView
            tintColor = ReaderPrefs.chrome(theme)
        }
        cancel.backdropSource = pageView
        cancel.usePopupOptics()
        if (showBack) {
            content.findViewById<GlassActionButton>(R.id.cardBack).apply {
                backdropSource = pageView
                usePopupOptics()
            }
        }
        glass.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val dialogWindow = dialog.window
        dialog.setContentView(glass)
        dialogWindow?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialogWindow?.setDimAmount(0f)
        dialogWindow?.setWindowAnimations(R.style.SettingsCardAnimation)
        dialogWindow?.setGravity(Gravity.CENTER)
        dialog.show()
        dialogWindow?.setLayout(dialogWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        if (highlightIndex in rowViews.indices) {
            // 当前章可能在列表深处，打开时直接滚到它附近
            val target = rowViews[highlightIndex]
            chapterScroll.post {
                chapterScroll.scrollTo(0, (target.top - (chapterScroll.height - target.height) / 2).coerceAtLeast(0))
            }
        }
        GlassStyle.load(this).applyTo(glass)
    }
    private fun showCatalog() {
        val doc = document ?: return
        stopPanelHeightAnimation()
        barsVisible = false
        pageView.setChromeVisible(false)
        listOf(topArea, bottomArea, settingsPanel).forEach { view ->
            view.animate().cancel()
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationY = 0f
        }
        panelSection = null
        settingsPanel.removeAllViews()
        applyChromeColors()
        scheduleAutoReading()
        if (doc.chapters.isEmpty()) {
            showGlassNotice(
                getString(R.string.reader_catalog),
                getString(R.string.reader_no_chapters),
                getString(R.string.ok)
            )
            return
        }
        val rows = doc.chapters.map { it.title }
        // 停在书名/前言这类章节之前的内容时返回 -1，此时目录不高亮任何一行
        val current = doc.chapterIndexAt(pageView.currentOffset())
        showCatalogPanel(doc, rows, current)
    }

    /** 目录面板：顶部「目录 / 书签」两个标签，共用一个玻璃卡片。 */
    private fun showCatalogPanel(doc: Document, rows: List<String>, current: Int) {
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        val accent = getColor(R.color.shelf_tab_selected)
        val card = cardSurface()
        val dialogRef = arrayOfNulls<Dialog>(1)

        // 顶部：返回 + 两个标签（选中项加红色下划线）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = GlassActionButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            useBackIcon(ink, 18)
            usePopupOptics()
            tintColor = ReaderPrefs.chrome(theme)
            backdropSource = pageView
            frosted = GlassStyle.load(this@ReaderActivity) == GlassStyle.FROSTED
            setGlassClickListener { dialogRef[0]?.dismiss() }
        }
        header.addView(back)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tabRow)
        // 右侧留出与返回按钮等宽的空位，让标签整体居中
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(1)) })
        card.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(scroll)

        val labels = listOf("目录", "书签")
        val underline = mutableListOf<View>()
        var selected = 0

        fun renderBookmarks() {
            content.removeAllViews()
            val marks = BookmarkStore.load(this, book.id)
            if (marks.isEmpty()) {
                content.addView(TextView(this).apply {
                    text = "暂无书签"
                    setTextColor(soft)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                })
                return
            }
            marks.forEach { mark ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(2), dp(10))
                }
                // 左侧：章节名 + 片段，点击跳转
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    setOnClickListener {
                        pageView.seekToOffset(mark.offset)
                        saveProgress(lastProgress)
                        dialogRef[0]?.dismiss()
                    }
                }
                texts.addView(TextView(this@ReaderActivity).apply {
                    text = mark.chapter.ifEmpty {
                        val percent = mark.offset * 100 / doc.text.length.coerceAtLeast(1)
                        "位置 $percent%"
                    }
                    setTextColor(ink)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                texts.addView(TextView(this@ReaderActivity).apply {
                    text = mark.snippet.ifEmpty { "…" }
                    setTextColor(soft)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(texts)
                // 右侧：删除
                row.addView(android.widget.ImageView(this@ReaderActivity).apply {
                    setImageResource(R.drawable.ic_trash)
                    setColorFilter(soft)
                    // 触摸区 30dp，内边距 6dp，图标实际画 18dp
                    val size = dp(30)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) }
                    isClickable = true
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    contentDescription = "删除书签"
                    setOnClickListener {
                        BookmarkStore.remove(this@ReaderActivity, book.id, mark.id)
                        Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                        renderBookmarks()
                    }
                })
                content.addView(row)
            }
        }

        fun renderChapters() {
            content.removeAllViews()
            rows.forEachIndexed { index, label ->
                content.addView(TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 15f
                    if (index == current) {
                        setTextColor(accent)
                        typeface = Typeface.DEFAULT_BOLD
                    } else {
                        setTextColor(ink)
                    }
                    val pad = dp(14)
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    setOnClickListener {
                        pageView.seekToOffset(doc.chapters[index].offset)
                        saveProgress(lastProgress)
                        dialogRef[0]?.dismiss()
                    }
                })
            }
        }

        fun selectTab(index: Int) {
            selected = index
            labels.forEachIndexed { i, _ ->
                (tabRow.getChildAt(i) as LinearLayout).getChildAt(0).let { view ->
                    val label = view as TextView
                    label.setTextColor(if (i == index) ink else soft)
                    label.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                (tabRow.getChildAt(i) as LinearLayout).getChildAt(1).visibility =
                    if (i == index) View.VISIBLE else View.INVISIBLE
            }
            if (index == 0) renderChapters() else renderBookmarks()
        }

        labels.forEachIndexed { index, label ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { selectTab(index) }
            }
            tab.addView(TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(soft)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            val line = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(2))
                    .apply { topMargin = dp(5) }
                setBackgroundColor(accent)
                visibility = View.INVISIBLE
            }
            tab.addView(line)
            underline.add(line)
            tabRow.addView(tab)
        }

        selectTab(selected)
        dialogRef[0] = showGlassPanel(card)
    }

    /** 把内容包进玻璃卡片弹出；anchorTopEnd 时贴在右上角，否则居中。 */
    private fun showGlassPanel(content: View, widthDp: Int = 345, anchorTopEnd: Boolean = false): Dialog {
        val dialog = Dialog(this)
        val glass = AgslGlassView(this).apply {
            useControlOptics(26f)
            usePopupOptics()
            backdropSource = pageView
            tintColor = ReaderPrefs.chrome(theme)
        }
        glass.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        dialog.setContentView(glass)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setWindowAnimations(R.style.SettingsCardAnimation)
            setGravity(if (anchorTopEnd) Gravity.TOP or Gravity.END else Gravity.CENTER)
        }
        val available = pageView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val width = minOf(dp(widthDp), (available - dp(32)).coerceAtLeast(1))
        if (anchorTopEnd) {
            dialog.window?.setLayout(width, FrameLayout.LayoutParams.WRAP_CONTENT)
            dialog.window?.setGravity(Gravity.TOP or Gravity.END)
            dialog.window?.attributes = dialog.window?.attributes?.apply {
                x = dp(12)
                y = dp(58)
            }
        }
        dialog.show()
        if (!anchorTopEnd) dialog.window?.setLayout(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        GlassStyle.load(this).applyTo(glass)
        return dialog
    }

    private fun cardSurface(): LinearLayout {
        val chrome = ReaderPrefs.chrome(theme)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 26 * resources.displayMetrics.density
                setColor((chrome and 0xFFFFFF) or 0xB8000000.toInt())
            }
        }
    }

    // ---------- 书籍信息：书名 / 作者 / 添加书签 / 搜全文 ----------

    private fun showBookInfo() {
        document ?: return
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        val card = cardSurface()

        // 头部：封面 + 书名 + 作者
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(BookCoverView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(64))
            bind(book)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        texts.addView(TextView(this).apply {
            text = book.title
            setTextColor(ink)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val author = book.author.trim()
        if (author.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = author
                setTextColor(soft)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        head.addView(texts)
        card.addView(head)

        // 两个动作：添加书签 / 搜全文
        val dialogRef = arrayOfNulls<Dialog>(1)
        fun action(iconRes: Int, label: String, onClick: () -> Unit): TextView {
            val icon = getDrawable(iconRes)?.mutate()?.apply { setTint(ink) }
            return TextView(this).apply {
                text = label
                setTextColor(soft)
                textSize = 11f
                gravity = Gravity.CENTER
                setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
                compoundDrawablePadding = dp(8)
                setPadding(dp(6), dp(14), dp(6), dp(10))
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        actions.addView(
            action(R.drawable.ic_bookmark, "添加书签") { addBookmarkNow() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            action(R.drawable.ic_header_search, "搜全文") {
                dialogRef[0]?.dismiss()
                showFullTextSearch()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(actions)

        dialogRef[0] = showGlassPanel(card, anchorTopEnd = true)
    }

    private fun addBookmarkNow() {
        val doc = document ?: return
        val offset = pageView.currentOffset()
        val added = BookmarkStore.add(
            this, book.id, offset, doc.chapterAt(offset), snippetAround(doc.text, offset, 0)
        )
        Toast.makeText(
            this,
            if (added) "已添加书签，可在目录的「书签」里查看" else "这个位置已经有书签了",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 取一段上下文，换行折成空格，供书签和搜索结果展示。 */
    private fun snippetAround(text: CharSequence, offset: Int, matchLength: Int): String {
        val start = (offset - 18).coerceAtLeast(0)
        val end = (offset + matchLength + 24).coerceAtMost(text.length)
        val raw = text.subSequence(start, end).toString().replace('\n', ' ').replace('\ufffc', ' ').trim()
        return buildString {
            if (start > 0) append('…')
            append(raw.replace(Regex("\\s+"), " "))
            if (end < text.length) append('…')
        }
    }

    // ---------- 本书内全文搜索 ----------

    /** 取命中位置所在的整句（以句末标点和换行为界，限制长度避免整段过长）。 */
    private fun sentenceAround(text: CharSequence, offset: Int, matchLength: Int): String {
        val stops = charArrayOf('。', '！', '？', '…', '；', '\n')
        var start = offset
        while (start > 0 && offset - start < 80 && text[start - 1] !in stops) start--
        var end = offset + matchLength
        while (end < text.length && end - (offset + matchLength) < 120 && text[end] !in stops) end++
        if (end < text.length) end++
        return text.subSequence(start, end).toString().replace('\n', ' ').trim()
    }

    /** 把句子里的关键词标成强调色并加粗。 */
    private fun highlightKeyword(sentence: String, keyword: String, color: Int): CharSequence {
        val spannable = android.text.SpannableString(sentence)
        if (keyword.isEmpty()) return spannable
        var index = sentence.indexOf(keyword)
        while (index >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                index, index + keyword.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                index, index + keyword.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            index = sentence.indexOf(keyword, index + keyword.length)
        }
        return spannable
    }

    // ---------- 本书内全文搜索：输入后点「搜索」，带历史 ----------

    private fun searchHistoryKey() = "history_" + book.id

    private fun loadSearchHistory(): MutableList<String> {
        val raw = getSharedPreferences("reader_search", MODE_PRIVATE)
            .getString(searchHistoryKey(), null) ?: return mutableListOf()
        return runCatching {
            val array = org.json.JSONArray(raw)
            MutableList(array.length()) { array.getString(it) }
        }.getOrDefault(mutableListOf())
    }

    private fun rememberSearch(keyword: String) {
        val list = loadSearchHistory()
        list.remove(keyword)
        list.add(0, keyword)
        val trimmed = list.take(20)
        val array = org.json.JSONArray()
        trimmed.forEach { array.put(it) }
        getSharedPreferences("reader_search", MODE_PRIVATE).edit()
            .putString(searchHistoryKey(), array.toString()).apply()
    }

    private fun clearSearchHistory() {
        getSharedPreferences("reader_search", MODE_PRIVATE).edit()
            .remove(searchHistoryKey()).apply()
    }

    private fun showFullTextSearch() {
        val doc = document ?: return
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        val accent = getColor(R.color.shelf_tab_selected)
        val card = cardSurface()
        val dialogRef = arrayOfNulls<Dialog>(1)

        // 标题
        card.addView(TextView(this).apply {
            text = "全文搜索"
            setTextColor(ink)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        // 输入行：放大镜 + 输入框 + 右侧「搜索」
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_glass_chip)
            setPadding(dp(12), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            )
        }
        inputRow.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_header_search)
            setColorFilter(soft)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        val input = EditText(this).apply {
            hint = "搜索本书内容"
            setTextColor(ink)
            setHintTextColor(soft)
            textSize = 14f
            maxLines = 1
            setSingleLine()
            setBackground(null)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        inputRow.addView(input)
        val searchButton = TextView(this).apply {
            text = "搜索"
            setTextColor(accent)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
        }
        inputRow.addView(searchButton)
        card.addView(inputRow)

        // 历史区：标题 + 垃圾桶 + 词条
        val historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        historyHeader.addView(TextView(this).apply {
            text = "搜索历史"
            setTextColor(ink)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val trash = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(soft)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            isClickable = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        historyHeader.addView(trash)
        historyBox.addView(historyHeader)
        val historyChips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyBox.addView(historyChips)
        card.addView(historyBox)

        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultsScroll = ScrollView(this).apply {
            addView(resultsBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        card.addView(resultsScroll)

        var runSearchRef: ((String) -> Unit)? = null
        val chipPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * resources.displayMetrics.scaledDensity
        }
        val rowWidth = dp(305).toFloat()

        // 历史词条：先横向排满一行再换行
        fun renderHistory() {
            historyChips.removeAllViews()
            val items = loadSearchHistory()
            historyBox.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            trash.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
            var row: LinearLayout? = null
            var used = 0f
            items.forEach { word ->
                val chipWidth = chipPaint.measureText(word) + dp(24)
                if (row == null || (used > 0f && used + chipWidth > rowWidth)) {
                    val created = LinearLayout(this@ReaderActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    historyChips.addView(created)
                    row = created
                    used = 0f
                }
                row?.addView(TextView(this@ReaderActivity).apply {
                    text = word
                    setTextColor(ink)
                    textSize = 12f
                    background = getDrawable(R.drawable.bg_glass_chip)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    isClickable = true
                    setOnClickListener {
                        input.setText(word)
                        input.setSelection(word.length)
                        runSearchRef?.invoke(word)
                    }
                })
                used += chipWidth + dp(8)
            }
        }

        fun runSearch(keyword: String) {
            val query = keyword.trim()
            resultsBox.removeAllViews()
            if (query.isEmpty()) {
                resultsScroll.visibility = View.GONE
                renderHistory()
                return
            }
            rememberSearch(query)
            historyBox.visibility = View.GONE
            resultsScroll.visibility = View.VISIBLE
            val text = doc.text
            val matches = mutableListOf<Int>()
            var cursor = text.indexOf(query, 0)
            var found = 0
            while (cursor >= 0 && found < 500) {
                val at = cursor
                val matchIndex = found
                matches.add(at)
                // 章节名：正文之前的命中显示「开篇」，其余按位置百分比兜底
                val chapter = doc.chapterAt(at).ifEmpty {
                    val firstChapter = doc.chapters.firstOrNull()?.offset ?: -1
                    if (firstChapter < 0) "本书"
                    else if (at < firstChapter) "开篇"
                    else "位置 " + (at * 100 / text.length.coerceAtLeast(1)) + "%"
                }
                resultsBox.addView(TextView(this@ReaderActivity).apply {
                    setText(chapter)
                    setTextColor(ink)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(14), dp(2), dp(6))
                })
                // 命中所在的那一句，关键词标红
                val sentence = sentenceAround(text, at, query.length)
                resultsBox.addView(TextView(this@ReaderActivity).apply {
                    setText(highlightKeyword(sentence, query, accent))
                    setTextColor(ink)
                    textSize = 12f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    background = getDrawable(R.drawable.bg_glass_chip)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        dialogRef[0]?.dismiss()
                        enterSearchMode(query, matches, matchIndex)
                    }
                })
                found++
                cursor = text.indexOf(query, at + query.length.coerceAtLeast(1))
            }
            if (found == 0) {
                resultsBox.addView(TextView(this@ReaderActivity).apply {
                    setText("没有找到「$query」")
                    setTextColor(soft)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, dp(20))
                })
            }
            resultsScroll.layoutParams = (resultsScroll.layoutParams as LinearLayout.LayoutParams)
                .apply { height = dp(300) }
        }

        runSearchRef = { runSearch(it) }
        searchButton.setOnClickListener { runSearch(input.text?.toString().orEmpty()) }
        input.setOnEditorActionListener { _, _, _ ->
            runSearch(input.text?.toString().orEmpty())
            true
        }
        trash.setOnClickListener {
            clearSearchHistory()
            renderHistory()
            Toast.makeText(this@ReaderActivity, "已清空搜索历史", Toast.LENGTH_SHORT).show()
        }

        renderHistory()
        dialogRef[0] = showGlassPanel(card)
        input.requestFocus()
    }

    // ---------- 搜索态：正文高亮 + 顶部提示条 + 底部导航 ----------

    private fun enterSearchMode(query: String, matches: List<Int>, index: Int) {
        if (document == null || matches.isEmpty()) return
        if (searchQuery == null) searchReturnOffset = pageView.currentOffset()
        searchQuery = query
        searchMatches = matches
        searchIndex = index.coerceIn(0, matches.size - 1)
        // 搜索态下收起常规顶栏/底栏，只留搜索相关的两条
        barsVisible = false
        pageView.setChromeVisible(false)
        topArea.visibility = View.GONE
        bottomArea.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        buildSearchChrome()
        showSearchChrome(true)
        hideNormalChromeForSearch()
        updateSearchChrome()
        gotoMatch(searchIndex, announce = false)
    }

    private var searchClose: TextView? = null
    private val searchNavItems = mutableListOf<TextView>()

    private fun buildSearchChrome() {
        val root = findViewById<FrameLayout>(R.id.root)
        if (searchTopChip == null) {
            val rootInsets = ViewCompat.getRootWindowInsets(root)
            val topInset = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
            val bottomInsetPx = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

            // 顶栏：真玻璃容器（和阅读底栏同一套 AGSL 材质），文字绝对居中
            val topGlass = AgslGlassView(this).apply {
                useControlOptics(20f)
                tintColor = ReaderPrefs.chrome(theme)
                backdropSource = pageView
                frosted = GlassStyle.load(this@ReaderActivity) == GlassStyle.FROSTED
                layoutParams = FrameLayout.LayoutParams(
                    dp(260), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    topMargin = dp(8) + topInset
                }
            }
            val chip = FrameLayout(this).apply {
                setPadding(dp(10), dp(8), dp(4), dp(8))
            }
            searchLabel = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(52), 0, dp(52), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END
                )
            }
            searchCounter = TextView(this).apply {
                textSize = 10.5f
                setPadding(dp(4), 0, dp(2), 0)
            }
            searchClose = TextView(this).apply {
                text = "✕"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(10), 0)
                isClickable = true
                setOnClickListener { exitSearchMode() }
            }
            right.addView(searchCounter)
            right.addView(searchClose)
            chip.addView(searchLabel)
            chip.addView(right)
            topGlass.addView(chip, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            root.addView(topGlass)
            searchTopChip = topGlass

            // 底栏：真玻璃容器，三段等分
            val navGlass = AgslGlassView(this).apply {
                useControlOptics(20f)
                tintColor = ReaderPrefs.chrome(theme)
                backdropSource = pageView
                frosted = GlassStyle.load(this@ReaderActivity) == GlassStyle.FROSTED
                layoutParams = FrameLayout.LayoutParams(
                    dp(260), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(24) + bottomInsetPx
                }
            }
            val nav = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun item(label: String, action: () -> Unit): TextView = TextView(this@ReaderActivity).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                setOnClickListener { action() }
            }
            searchNavItems.add(item("⟲ 原进度") {
                pageView.seekToOffset(searchReturnOffset)
                saveProgress(lastProgress)
            })
            searchNavItems.add(item("← 上一个") { stepMatch(-1) })
            searchNavItems.add(item("下一个 →") { stepMatch(1) })
            searchNavItems.forEach { nav.addView(it) }
            navGlass.addView(nav, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            root.addView(navGlass)
            searchNavBar = navGlass
        }
        styleSearchChrome()
    }

    /** 搜索态两条栏跟随当前主题配色（切换夜间时也会重新调用）。 */
    private fun styleSearchChrome() {
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        val frosted = GlassStyle.load(this) == GlassStyle.FROSTED
        listOfNotNull(searchTopChip, searchNavBar).forEach { bar ->
            (bar as? AgslGlassView)?.apply {
                tintColor = ReaderPrefs.chrome(theme)
                backdropSource = pageView
                this.frosted = frosted
            }
        }
        searchLabel?.setTextColor(ink)
        searchCounter?.setTextColor(soft)
        searchClose?.setTextColor(ink)
        searchNavItems.forEach { it.setTextColor(ink) }
    }

    private fun showSearchChrome(visible: Boolean) {
        searchTopChip?.visibility = if (visible) View.VISIBLE else View.GONE
        searchNavBar?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateSearchChrome() {
        searchLabel?.text = "搜全文「${searchQuery.orEmpty()}」"
        searchCounter?.text = if (searchMatches.isEmpty()) "" else "${searchIndex + 1}/${searchMatches.size}"
    }

    private fun stepMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = (searchIndex + delta).mod(searchMatches.size)
        updateSearchChrome()
        gotoMatch(searchIndex, announce = true)
    }

    private fun gotoMatch(index: Int, announce: Boolean) {
        val offset = searchMatches.getOrNull(index) ?: return
        pageView.seekToOffset(offset)
        // 只高亮当前这一处；翻到下一处时旧的会被清掉
        pageView.setSearchHit(
            offset, searchQuery?.length ?: 0, getColor(R.color.search_highlight)
        )
        if (announce) updateSearchChrome()
    }

    private fun exitSearchMode() {
        searchQuery = null
        searchMatches = emptyList()
        pageView.setSearchHit(-1, 0, 0)
        saveProgress(lastProgress)
        showSearchChrome(false)
        Toast.makeText(this, "已退出搜索", Toast.LENGTH_SHORT).show()
    }

    private fun saveProgress(progress: Float) {
        runCatching { BookStore.updateProgress(this, book.id, progress, pageView.currentOffset()) }
    }

    private fun changeTheme(value: Int) {
        ReaderPrefs.setTheme(this, value)
        applyTheme(value)
    }

    private fun applyTheme(value: Int) {
        theme = value
        pageView.setTheme(value)
        applyChromeColors()
        panelSection?.let { showSettings(it, refresh = true) }
    }

    private fun applyLayoutPreferences() {
        pageView.setTurnMode(activeTurnMode())
        pageView.setDoubleColumns(ReaderPrefs.doubleColumns(this))
    }

    private fun activeTurnMode() = if (autoReading) ReaderPrefs.MODE_SCROLL else ReaderPrefs.turnMode(this)

    private fun applyBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = if (ReaderPrefs.followsBrightness(this@ReaderActivity)) -1f
                else (ReaderPrefs.brightness(this@ReaderActivity) / 100f).coerceIn(0.01f, 1f)
        }
    }

    private fun scheduleAutoReading() {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(autoTick)
        val running = autoReading && readerResumed && !barsVisible && document != null && hasWindowFocus()
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (running) window.addFlags(flag) else window.clearFlags(flag)
        if (!running) return
        autoFrameNanos = 0L
        autoPendingPixels = 0f
        autoPixelsPerSecond = ReaderPrefs.autoSpeed(this) * resources.displayMetrics.density
        choreographer.postFrameCallback(autoTick)
    }

    private fun advanceAutomaticReading(frameNanos: Long) {
        if (pageView.isAtEnd()) {
            autoReading = false
            applyLayoutPreferences()
            document?.let { pageView.seekToOffset(it.text.length) }
            scheduleAutoReading()
            return
        }
        if (autoFrameNanos != 0L) {
            val elapsed = (frameNanos - autoFrameNanos).coerceIn(0L, 100_000_000L)
            autoPendingPixels += autoPixelsPerSecond * (elapsed / 1_000_000_000f)
        }
        autoFrameNanos = frameNanos
        val pixels = autoPendingPixels.toInt()
        if (pixels > 0) {
            autoPendingPixels -= pixels
            pageView.advanceScroll(pixels.toFloat())
        }
        Choreographer.getInstance().postFrameCallback(autoTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        scheduleAutoReading()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (document != null && ReaderPrefs.volumeTurns(this) &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) pageView.prevPage() else pageView.nextPage()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (document != null) outState.putInt("readingOffset", pageView.currentOffset())
        else restoredOffset?.let { outState.putInt("readingOffset", it) }
        outState.putBoolean("autoReading", autoReading)
        super.onSaveInstanceState(outState)
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var globalSwipeDone = false
    private var chromeCurlDragging = false
    private val pageLocation = IntArray(2)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                globalSwipeDone = false
                chromeCurlDragging = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!globalSwipeDone) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    val mode = activeTurnMode()
                    val vertical = mode == ReaderPrefs.MODE_VERTICAL || mode == ReaderPrefs.MODE_SCROLL
                    val travel = if (vertical) dy else dx
                    val across = if (vertical) dx else dy
                    if (kotlin.math.abs(travel) > dp(48) && kotlin.math.abs(travel) > kotlin.math.abs(across) * 1.4f) {
                        // 落在栏/胶囊上时由 Activity 接管；面板（含滑杆）打开时不接管
                        val panelOpen = settingsPanel.visibility == View.VISIBLE
                        val onChrome = listOf(
                            R.id.topBar, R.id.backBar, R.id.bottomBar
                        ).any { id ->
                            val v = findViewById<View>(id) ?: return@any false
                            if (!v.isShown) return@any false
                            v.getLocationOnScreen(pageLocation)
                            downRawX >= pageLocation[0] && downRawX <= pageLocation[0] + v.width &&
                                downRawY >= pageLocation[1] && downRawY <= pageLocation[1] + v.height
                        }
                        if (onChrome && !panelOpen) {
                            globalSwipeDone = true
                            val cancel = MotionEvent.obtain(ev)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            pageView.getLocationOnScreen(pageLocation)
                            val y = downRawY - pageLocation[1]
                            if (pageView.usesCurl) {
                                chromeCurlDragging = true
                                val down = MotionEvent.obtain(
                                    ev.downTime, ev.downTime, MotionEvent.ACTION_DOWN,
                                    downRawX - pageLocation[0], y, ev.metaState
                                )
                                pageView.onTouchEvent(down)
                                down.recycle()
                            } else if (travel < 0) {
                                pageView.nextPage(y)
                            } else {
                                pageView.prevPage(y)
                            }
                        }
                    }
                }
            }
        }
        if (chromeCurlDragging) {
            pageView.getLocationOnScreen(pageLocation)
            val local = MotionEvent.obtain(ev)
            local.setLocation(ev.rawX - pageLocation[0], ev.rawY - pageLocation[1])
            pageView.onTouchEvent(local)
            local.recycle()
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                chromeCurlDragging = false
            }
            return true
        }
        return globalSwipeDone || super.dispatchTouchEvent(ev)
    }
    override fun onResume() {
        super.onResume()
        readerResumed = true
        if (::pageView.isInitialized) {
            val savedTheme = ReaderPrefs.theme(this)
            if (savedTheme != theme) applyTheme(savedTheme)
        }
        if (document != null) readingStartedAt = SystemClock.elapsedRealtime()
        scheduleAutoReading()
    }

    override fun onPause() {
        readerResumed = false
        scheduleAutoReading()
        readingStartedAt?.let {
            ReadingStats.record(this, System.currentTimeMillis(), SystemClock.elapsedRealtime() - it, bookId = book.id)
        }
        readingStartedAt = null
        super.onPause()
        if (document != null) saveProgress(lastProgress)
    }

}

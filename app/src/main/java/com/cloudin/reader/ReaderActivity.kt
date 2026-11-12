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
        barsVisible = !barsVisible
        pageView.setChromeVisible(barsVisible)
        if (!barsVisible) hideSettingsPanel()
        scheduleAutoReading()
        animateBar(topArea, barsVisible)
        animateBar(bottomArea, barsVisible)
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
        showGlassNotice(
            getString(R.string.reader_catalog),
            null,
            null,
            rows,
            highlightIndex = current,
            showBack = true
        ) { index ->
            val offset = doc.chapters[index].offset
            pageView.seekToOffset(offset)
            saveProgress(lastProgress)
        }
    }

    /** 把内容包进玻璃卡片并居中弹出，尺寸沿用目录弹窗那一套。 */
    private fun showGlassPanel(content: View, widthDp: Int = 345): Dialog {
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
            setGravity(Gravity.CENTER)
        }
        val available = pageView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val width = minOf(dp(widthDp), (available - dp(32)).coerceAtLeast(1))
        dialog.show()
        dialog.window?.setLayout(width, FrameLayout.LayoutParams.WRAP_CONTENT)
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

        // 书签区：加了就刷新，点条目跳过去，长按删除
        val sectionTitle = TextView(this).apply {
            setTextColor(soft)
            textSize = 11f
            setPadding(dp(2), dp(16), 0, dp(4))
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }

        fun renderBookmarks() {
            rows.removeAllViews()
            val marks = BookmarkStore.load(this@ReaderActivity, book.id)
            sectionTitle.visibility = if (marks.isEmpty()) View.GONE else View.VISIBLE
            sectionTitle.text = "书签 · ${marks.size}"
            // 列表超过一定高度就内部滚动
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = if (marks.size > 4) dp(200) else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            marks.forEach { mark ->
                rows.addView(TextView(this@ReaderActivity).apply {
                    text = buildString {
                        if (mark.chapter.isNotEmpty()) append(mark.chapter).append("　")
                        append(mark.snippet)
                    }
                    setTextColor(ink)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(9), dp(2), dp(9))
                    isClickable = true
                    setOnClickListener {
                        pageView.seekToOffset(mark.offset)
                        saveProgress(lastProgress)
                        dialogRef[0]?.dismiss()
                    }
                    setOnLongClickListener {
                        BookmarkStore.remove(this@ReaderActivity, book.id, mark.id)
                        Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                        renderBookmarks()
                        true
                    }
                })
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        actions.addView(
            action(R.drawable.ic_bookmark, "添加书签") {
                addBookmarkNow { renderBookmarks() }
            },
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
        card.addView(sectionTitle)
        card.addView(scroll)
        renderBookmarks()

        dialogRef[0] = showGlassPanel(card)
    }

    private fun addBookmarkNow(onChanged: () -> Unit) {
        val doc = document ?: return
        val offset = pageView.currentOffset()
        val added = BookmarkStore.add(
            this, book.id, offset, doc.chapterAt(offset), snippetAround(doc.text, offset, 0)
        )
        Toast.makeText(this, if (added) "已添加书签" else "这个位置已经有书签了", Toast.LENGTH_SHORT).show()
        if (added) onChanged()
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

    private fun showFullTextSearch() {
        val doc = document ?: return
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        val card = cardSurface()

        card.addView(TextView(this).apply {
            text = "搜全文"
            setTextColor(ink)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        val input = EditText(this).apply {
            hint = "输入关键词，搜索本书内容"
            setTextColor(ink)
            setHintTextColor(soft)
            textSize = 14f
            maxLines = 1
            setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.bg_glass_chip)
        }
        card.addView(input)

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(results, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }
        card.addView(scroll)

        val dialogRef = arrayOfNulls<Dialog>(1)

        fun search(query: String) {
            results.removeAllViews()
            val keyword = query.trim()
            if (keyword.isEmpty()) {
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                return
            }
            val text = doc.text
            var cursor = text.indexOf(keyword, 0)
            var found = 0
            while (cursor >= 0 && found < 50) {
                val at = cursor
                results.addView(TextView(this@ReaderActivity).apply {
                    setText(snippetAround(text, at, keyword.length))
                    setTextColor(ink)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                    isClickable = true
                    setOnClickListener {
                        pageView.seekToOffset(at)
                        saveProgress(lastProgress)
                        dialogRef[0]?.dismiss()
                    }
                })
                found++
                cursor = text.indexOf(keyword, at + keyword.length.coerceAtLeast(1))
            }
            if (found == 0) {
                results.addView(TextView(this@ReaderActivity).apply {
                    setText("没有找到「$keyword」")
                    setTextColor(soft)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                })
            }
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(240)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty())
        })

        dialogRef[0] = showGlassPanel(card)
        input.requestFocus()
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

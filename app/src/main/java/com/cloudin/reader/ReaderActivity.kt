package com.cloudin.reader

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

class ReaderActivity : AppCompatActivity() {

    private lateinit var book: Book
    private lateinit var pageView: PageView
    private lateinit var topArea: View
    private lateinit var bottomArea: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var settingsPanel: com.example.liquidglass.LiquidGlassView
    private var panelSection = -1
    private lateinit var progressRow: View
    private lateinit var loading: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtChapter: TextView
    private lateinit var txtProgress: TextView
    private lateinit var seekBar: SeekBar
    private var document: Document? = null
    private var theme = ReaderPrefs.THEME_PAPER
    private var lastProgress = 0f
    private var barsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        bottomBar = findViewById(R.id.bottomBar)
        settingsPanel = findViewById(R.id.settingsPanel)
        progressRow = findViewById(R.id.progressRow)
        loading = findViewById(R.id.loading)
        txtTitle = findViewById(R.id.txtTitle)
        txtChapter = findViewById(R.id.txtChapter)
        txtProgress = findViewById(R.id.txtProgress)
        seekBar = findViewById(R.id.seekBar)
        txtTitle.text = book.title

        theme = ReaderPrefs.theme(this)
        pageView.setTheme(theme)
        pageView.setFontSize(ReaderPrefs.fontSize(this).toFloat())
        pageView.setMargin(ReaderPrefs.margin(this))
        pageView.setLineSpacing(ReaderPrefs.lineSpacing(this))
        pageView.setTurnMode(ReaderPrefs.turnMode(this))
        applyChromeColors()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        setupBottomTabs()

        pageView.onCenterTap = { toggleBars() }
        pageView.onPageChanged = { _, _, progress ->
            lastProgress = progress
            updateProgressUi(progress)
        }
        pageView.onChapterChanged = { chapter -> txtChapter.text = chapter }
        val glassTop = findViewById<View>(R.id.topBar)
        val glassBack = findViewById<View>(R.id.backBar)
        listOf(glassTop, glassBack).forEach { bar ->
            (bar as? com.example.liquidglass.LiquidGlassView)?.apply {
                backdropSource = pageView
                enableDynamicBackground = true
            }
        }
        val glassBottom = findViewById<View>(R.id.bottomBar)
        val liquidBottom = glassBottom as? com.example.liquidglass.LiquidGlassView
        if (liquidBottom != null) {
            liquidBottom.backdropSource = pageView
            liquidBottom.enableDynamicBackground = true
            liquidBottom.blurAmount = 0.08f
            liquidBottom.material = com.example.liquidglass.GlassMaterial.REGULAR
            liquidBottom.saturation = 160f
            liquidBottom.adaptiveLensScale = true
            liquidBottom.refractionHeight = dp(12).toFloat()
            liquidBottom.bevelWidth = dp(24).toFloat()
            liquidBottom.dispersionStrength = 0.12f
            liquidBottom.enableEdgeHighlight = true
        }

        val layoutListener = View.OnLayoutChangeListener { _, l, t, rr, b, ol, ot, orr, ob ->
            if (rr - l > 0 && b - t > 0 && (rr - l != orr - ol || b - t != ob - ot)) refreshBackdrop()
        }


        pageView.post { refreshBackdrop() }

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
            val doc = DocumentLoader.load(book)
            runOnUiThread {
                if (doc == null) {
                    loading.visibility = View.GONE
                    showGlassNotice(
                        "这本书打不开了",
                        "文件可能已损坏或被删除。",
                        "确定"
                    ) { finish() }
                    return@runOnUiThread
                }
                document = doc
                val start = (book.progress * doc.text.length).toInt()
                pageView.setDocument(doc, start)
                loading.visibility = View.GONE
                txtChapter.text = doc.chapterAt(start)
                updateProgressUi(book.progress)
                refreshBackdrop()
            }
        }.start()
    }

    private fun updateProgressUi(progress: Float) {
        val p = (progress * 1000).toInt().coerceIn(0, 1000)
        if (seekBar.progress != p) seekBar.progress = p
        txtProgress.text = "${(progress * 100).toInt()}%"
    }

    private fun applyChromeColors() {
        val barColor = ReaderPrefs.background(theme)
        val ink = ReaderPrefs.ink(theme)
        val soft = ReaderPrefs.softInk(theme)
        topBar.setBackgroundColor(Color.TRANSPARENT)
        if (bottomBar is GlassBar) bottomBar.setBackgroundColor(barColor)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val lightIcons = theme != ReaderPrefs.THEME_NIGHT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
        txtTitle.setTextColor(ink)
        txtChapter.setTextColor(soft)
        txtProgress.setTextColor(soft)
        loading.setTextColor(soft)
        findViewById<View>(R.id.root).setBackgroundColor(ReaderPrefs.background(theme))
        val tabs = listOf(R.id.tabCatalog, R.id.tabProgress, R.id.tabTheme, R.id.tabFont, R.id.tabTurn)
        tabs.forEach { id ->
            val group = findViewById<LinearLayout>(id)
            (group.getChildAt(0) as? TextView)?.setTextColor(ink)
            (group.getChildAt(1) as? TextView)?.setTextColor(soft)
        }
    }

    /** 点击交给玻璃本身，这样能触发 LiquidGlassView 的弹性按压动画 */
    private fun setupBottomTabs() {
        val bar = findViewById<View>(R.id.bottomBar) ?: return
        val ids = listOf(R.id.tabCatalog, R.id.tabProgress, R.id.tabTheme, R.id.tabFont, R.id.tabTurn)
        ids.forEach { id ->
            val tab = findViewById<View>(id)
            tab.isClickable = false
            tab.isFocusable = false
        }
        bar.isClickable = true
        val actions = listOf<() -> Unit>(
            { showCatalog() },
            { toggleProgressRow() },
            { showSettings(0) },
            { showSettings(1) },
            { showSettings(2) }
        )
        bar.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val rel = ev.rawX - loc[0]
                    val idx = (rel / (v.width / ids.size.toFloat())).toInt().coerceIn(0, ids.size - 1)
                    actions[idx]()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleProgressRow() {
        hideSettingsPanel()
        val glass = progressRow as com.example.liquidglass.LiquidGlassView
        if (progressRow.visibility == View.VISIBLE) {
            progressRow.animate()
                .alpha(0f)
                .translationY(dp(16).toFloat())
                .setDuration(130)
                .withEndAction {
                    progressRow.visibility = View.GONE
                    progressRow.alpha = 1f
                    progressRow.translationY = 0f
                }
                .start()
        } else {
            glass.cornerRadius = dp(24).toFloat()
            glass.backdropSource = pageView
            glass.enableDynamicBackground = true
            progressRow.visibility = View.VISIBLE
            progressRow.alpha = 0f
            progressRow.translationY = dp(18).toFloat()
            progressRow.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(190)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshBackdrop() {
        runCatching {
            (findViewById<View>(R.id.topBar) as? GlassBar)?.captureFrom(pageView)
            (findViewById<View>(R.id.backBar) as? GlassBar)?.captureFrom(pageView)
            (findViewById<View>(R.id.bottomBar) as? GlassBar)?.captureFrom(pageView)
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        if (!barsVisible) {
            progressRow.visibility = View.GONE
            hideSettingsPanel()
        }
        animateBar(topArea, barsVisible)
        animateBar(bottomArea, barsVisible)
        if (barsVisible) pageView.postDelayed({ refreshBackdrop() }, 60L)
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

    private fun showSettings(section: Int) {
        progressRow.visibility = View.GONE
        if (panelSection == section && settingsPanel.visibility == View.VISIBLE) {
            hideSettingsPanel()
            return
        }
        panelSection = section
        val content = layoutInflater.inflate(R.layout.sheet_reader_settings, settingsPanel, false)
        settingsPanel.removeAllViews()
        settingsPanel.addView(content)
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.cornerRadius = 24 * resources.displayMetrics.density
        settingsPanel.backdropSource = pageView
        settingsPanel.enableDynamicBackground = true
        settingsPanel.translationY = if (settingsPanel.height > 0) settingsPanel.height.toFloat() else 420f
        settingsPanel.animate()
            .translationY(0f)
            .setDuration(180)
            .start()


        content.findViewById<View>(R.id.sectionFont).visibility =
            if (section == 1) View.VISIBLE else View.GONE
        content.findViewById<View>(R.id.sectionTheme).visibility =
            if (section == 0) View.VISIBLE else View.GONE
        content.findViewById<View>(R.id.sectionTurn).visibility =
            if (section == 2) View.VISIBLE else View.GONE

        val sliderFont = content.findViewById<WeChatSlider>(R.id.sliderFont)
        sliderFont.applyTheme(theme)
        sliderFont.min = 14
        sliderFont.max = 32
        sliderFont.value = ReaderPrefs.fontSize(this).coerceIn(14, 32)
        sliderFont.leftText = "A"
        sliderFont.rightText = "A"
        sliderFont.onChange = { size ->
            ReaderPrefs.setFontSize(this, size)
            pageView.setFontSize(size.toFloat())
        }

        val sliderMargin = content.findViewById<WeChatSlider>(R.id.sliderMargin)
        sliderMargin.applyTheme(theme)
        sliderMargin.min = 0
        sliderMargin.max = 100
        sliderMargin.value = ReaderPrefs.margin(this)
        sliderMargin.leftText = "小"
        sliderMargin.rightText = "大"
        sliderMargin.thumbText = "边距"
        sliderMargin.onChange = { v ->
            ReaderPrefs.setMargin(this, v)
            pageView.setMargin(v)
        }

        val sliderLine = content.findViewById<WeChatSlider>(R.id.sliderLine)
        sliderLine.applyTheme(theme)
        sliderLine.min = 0
        sliderLine.max = 100
        sliderLine.value = ReaderPrefs.lineSpacing(this)
        sliderLine.leftText = "紧"
        sliderLine.rightText = "松"
        sliderLine.thumbText = "行距"
        sliderLine.onChange = { v ->
            ReaderPrefs.setLineSpacing(this, v)
            pageView.setLineSpacing(v)
        }

        val swatches = mapOf(
            ReaderPrefs.THEME_PAPER to content.findViewById<View>(R.id.swatchPaper),
            ReaderPrefs.THEME_WHITE to content.findViewById<View>(R.id.swatchWhite),
            ReaderPrefs.THEME_NIGHT to content.findViewById<View>(R.id.swatchNight),
            ReaderPrefs.THEME_GREEN to content.findViewById<View>(R.id.swatchWood)
        )
        val themeHosts = mapOf(
            ReaderPrefs.THEME_PAPER to content.findViewById<View>(R.id.themePaper),
            ReaderPrefs.THEME_WHITE to content.findViewById<View>(R.id.themeWhite),
            ReaderPrefs.THEME_NIGHT to content.findViewById<View>(R.id.themeNight),
            ReaderPrefs.THEME_GREEN to content.findViewById<View>(R.id.themeWood)
        )
        fun paintSwatches() {
            swatches.forEach { (t, view) ->
                val selected = t == theme
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ReaderPrefs.swatchColor(t))
                    setStroke(
                        (if (selected) 3 else 1) * resources.displayMetrics.density.toInt(),
                        if (selected) Color.parseColor("#E0A458") else Color.parseColor("#D8D8D8")
                    )
                }
            }
        }
        paintSwatches()
        themeHosts.forEach { (t, host) ->
            host.setOnClickListener {
                theme = t
                ReaderPrefs.setTheme(this, t)
                pageView.setTheme(t)
                applyChromeColors()
                settingsPanel.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                sliderFont.applyTheme(t)
                sliderMargin.applyTheme(t)
                sliderLine.applyTheme(t)
                paintSwatches()
            }
        }

        val chips = mapOf(
            ReaderPrefs.MODE_NONE to content.findViewById<TextView>(R.id.turnNone),
            ReaderPrefs.MODE_SLIDE to content.findViewById<TextView>(R.id.turnSlide),
            ReaderPrefs.MODE_CURL to content.findViewById<TextView>(R.id.turnCurl),
            ReaderPrefs.MODE_FADE to content.findViewById<TextView>(R.id.turnFade)
        )
        var chipMode = ReaderPrefs.turnMode(this)
        fun paintChips() {
            chips.forEach { (m, chip) ->
                val selected = m == chipMode
                chip.setTextColor(if (selected) Color.parseColor("#E0A458") else ReaderPrefs.ink(theme))
                chip.background = if (selected) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8 * resources.displayMetrics.density
                        setColor(Color.parseColor("#FBEFDC"))
                        setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#E0A458"))
                    }
                } else {
                    getDrawable(R.drawable.bg_chip)
                }
            }
        }
        paintChips()
        chips.forEach { (m, chip) ->
            chip.setOnClickListener {
                chipMode = m
                ReaderPrefs.setTurnMode(this, m)
                pageView.setTurnMode(m)
                paintChips()
            }
        }
    }

    private fun hideSettingsPanel() {
        if (settingsPanel.visibility != View.VISIBLE) return
        panelSection = -1
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
        rows: List<Pair<String, Int>> = emptyList(),
        onPick: ((Int) -> Unit)? = null
    ) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = title
        if (!message.isNullOrEmpty()) {
            val m = content.findViewById<TextView>(R.id.cardMessage)
            m.text = message
            m.visibility = View.VISIBLE
        }
        val container = content.findViewById<LinearLayout>(R.id.cardRows)
        rows.forEachIndexed { index, pair ->
            val tv = TextView(this)
            tv.text = pair.first
            tv.gravity = Gravity.CENTER
            tv.textSize = 15f
            tv.setTextColor(pair.second)
            val v = (14 * resources.displayMetrics.density).toInt()
            tv.setPadding(v, v, v, v)
            tv.isClickable = true
            tv.setOnClickListener {
                dialog.dismiss()
                onPick?.invoke(index)
            }
            container.addView(tv)
        }
        val cancel = content.findViewById<TextView>(R.id.cardCancel)
        if (!okText.isNullOrEmpty()) {
            cancel.text = okText
            cancel.setOnClickListener {
                dialog.dismiss()
                onPick?.invoke(-1)
            }
        } else {
            cancel.text = "取消"
            cancel.setOnClickListener { dialog.dismiss() }
        }
        content.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 26 * resources.displayMetrics.density
            setColor(0x8CFFFFFF.toInt())
        }
        val glass = com.example.liquidglass.LiquidGlassView(this)
        glass.cornerRadius = 26 * resources.displayMetrics.density
        glass.blurAmount = 0.18f
        glass.material = com.example.liquidglass.GlassMaterial.REGULAR
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val backing = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * resources.displayMetrics.density
                setColor(0x8CFFFFFF.toInt())
            }
        }
        glass.addView(backing, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        glass.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val dialogWindow = dialog.window
        dialog.setContentView(glass)
        dialogWindow?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialogWindow?.setDimAmount(0f)
        dialogWindow?.setWindowAnimations(0)
        val dlgW = (345 * resources.displayMetrics.density).toInt()
        val dlgH = (resources.displayMetrics.heightPixels * 0.66f).toInt()
        dialogWindow?.setLayout(dlgW, dlgH)
        dialogWindow?.setGravity(Gravity.CENTER)
        dialog.show()
        glass.backdropSource = findViewById(android.R.id.content)
        glass.enableDynamicBackground = true
    }
    private var progressDialog: Dialog? = null
    private var progressSeek: SeekBar? = null

    private fun showProgressBlurDialog() {
        progressDialog?.dismiss()
        val dialog = Dialog(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = (18 * resources.displayMetrics.density).toInt()
        row.setPadding(pad, pad, pad, pad)
        val tv = TextView(this)
        tv.text = "${(lastProgress * 100).toInt()}%"
        tv.textSize = 12f
        tv.setTextColor(0xFF555555.toInt())
        row.addView(tv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = (lastProgress * 1000).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tv.text = "${progress / 10}%"
                    val doc = document ?: return
                    pageView.seekToOffset((progress / 1000f * doc.text.length).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row.addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(24).toFloat()
        bg.setColor(0xCFFFFFFF.toInt())
        bg.setStroke(dp(1), 0xE6FFFFFF.toInt())
        row.background = bg
        row.layoutParams = android.view.ViewGroup.LayoutParams(
            (720 * resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setContentView(row)

        val w = dialog.window
        w?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val lp = w!!.attributes
        lp.width = (720 * resources.displayMetrics.density).toInt()
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.BOTTOM
        lp.y = dp(96)
        lp.dimAmount = 0f
        lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            lp.blurBehindRadius = dp(40)
        }
        w.attributes = lp
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            w.setBackgroundBlurRadius(dp(40))
        }
        dialog.show()
        progressSeek = sb
        progressDialog = dialog
    }
    private fun showCatalog() {
        val doc = document ?: return
        if (doc.chapters.isEmpty()) {
            showGlassNotice(
                "目录",
                "TXT 文件没有识别到章节，可以用「进度」滑条跳转。",
                "确定"
            )
            return
        }
        val rows = doc.chapters.map { it.title to 0xFF222222.toInt() }
        showGlassNotice("目录", null, null, rows) { index ->
            val offset = doc.chapters[index].offset
            pageView.seekToOffset(offset)
            saveProgress(offset.toFloat() / doc.text.length)
        }
    }

    private fun saveProgress(progress: Float) {
        runCatching { BookStore.updateProgress(this, book.id, progress) }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var globalSwipeDone = false
    private val pageLocation = IntArray(2)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                globalSwipeDone = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!globalSwipeDone) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                        // 落在栏/胶囊上时由 Activity 接管；面板（含滑杆）打开时不接管
                        val panelOpen = settingsPanel.visibility == View.VISIBLE ||
                            progressRow.visibility == View.VISIBLE
                        val onChrome = listOf(
                            R.id.topBar, R.id.backBar, R.id.bottomBar
                        ).any { id ->
                            val v = findViewById<View>(id) ?: return@any false
                            if (v.visibility != View.VISIBLE) return@any false
                            v.getLocationOnScreen(pageLocation)
                            downRawX >= pageLocation[0] && downRawX <= pageLocation[0] + v.width &&
                                downRawY >= pageLocation[1] && downRawY <= pageLocation[1] + v.height
                        }
                        if (onChrome && !panelOpen) {
                            globalSwipeDone = true
                            if (dx < 0) pageView.nextPage() else pageView.prevPage()
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    override fun onPause() {
        super.onPause()
        if (document != null) saveProgress(lastProgress)
    }

}
package com.cloudin.reader

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class Tab { SHELF, GROUPS, FAVORITE, PROFILE }

    private enum class ShelfFormat(val key: String, val label: Int) {
        ALL("all", R.string.shelf_format_all),
        TXT("txt", R.string.shelf_format_txt),
        EPUB("epub", R.string.shelf_format_epub)
    }

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: BookshelfAdapter
    private lateinit var title: TextView
    private lateinit var btnBack: GlassActionButton
    private lateinit var actionSheet: View
    private lateinit var selectionActions: View
    private lateinit var actions: View
    private lateinit var btnMode: GlassActionButton
    private lateinit var btnFormat: GlassActionButton
    private lateinit var bottomBarView: View
    private lateinit var profilePage: View

    private val books = mutableListOf<Book>()
    private val groups = mutableListOf<BookGroup>()
    private var importing = false
    private var layoutMode = ShelfLayout.GRID
    private var shelfFormat = ShelfFormat.ALL
    private var currentTab = Tab.SHELF
    private var currentGroup: BookGroup? = null
    private var openedBookId: String? = null
    private var bottomInset = 0

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        importUris(uris.map { it to (queryName(it) ?: "book") })
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) importFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedBookId = savedInstanceState?.getString("opened_book_id")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        val header = findViewById<View>(R.id.header)
        val bottomBarViewLocal = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bottomInset = bars.bottom
            header.setPadding(header.paddingLeft, bars.top, header.paddingRight, header.paddingBottom)
            val barLp = bottomBarViewLocal.layoutParams as android.view.ViewGroup.MarginLayoutParams
            barLp.bottomMargin = bars.bottom
            bottomBarViewLocal.layoutParams = barLp
            (findViewById<View>(R.id.actionSheet)?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { sheetLp ->
                sheetLp.bottomMargin = dp(16) + bars.bottom
                findViewById<View>(R.id.actionSheet)?.layoutParams = sheetLp
            }
            insets
        }

        title = findViewById(R.id.title)
        btnBack = findViewById(R.id.btnBack)
        actions = findViewById(R.id.actions)
        actionSheet = findViewById(R.id.actionSheet)
        selectionActions = findViewById(R.id.selectionActions)
        btnMode = findViewById(R.id.btnMode)
        btnFormat = findViewById(R.id.btnFormat)
        bottomBarView = findViewById(R.id.bottomBar)

        layoutMode = if (prefs().getString("shelf_layout", "grid") == "list")
            ShelfLayout.LIST else ShelfLayout.GRID
        val savedFormat = prefs().getString("shelf_format", null)
        shelfFormat = ShelfFormat.entries.firstOrNull { it.key == savedFormat } ?: ShelfFormat.ALL

        setupRecycler()
        recycler.edgeEffectFactory = object : androidx.recyclerview.widget.RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(
                view: androidx.recyclerview.widget.RecyclerView,
                direction: Int
            ): android.widget.EdgeEffect {
                val e = android.widget.EdgeEffect(view.context)
                e.color = 0x00000000
                return e
            }
        }
        val headerBackdrop = findViewById<View>(R.id.bgLayer)
        listOf(R.id.btnBack, R.id.btnMode, R.id.btnFormat, R.id.btnSearch, R.id.btnImport, R.id.btnSelectAll, R.id.btnCancelSelect)
            .forEach { id ->
                findViewById<GlassActionButton>(id).apply {
                    backdropSource = headerBackdrop
                    val horizontalPadding = when (id) {
                        R.id.btnBack, R.id.btnMode, R.id.btnImport -> 0
                        R.id.btnFormat, R.id.btnSearch -> dp(10)
                        else -> dp(14)
                    }
                    textView.setPadding(horizontalPadding, 0, horizontalPadding, 0)
                }
            }
        findViewById<AgslGlassView>(R.id.actionSheet).apply {
            useControlOptics(28f)
            backdropSource = root.findViewById(R.id.shelfBackdrop)
        }
        findViewById<ShelfTabBar>(R.id.bottomBar).apply {
            setBackdropSource(root.findViewById(R.id.shelfBackdrop))
            setTabs(
                listOf(
                    ShelfTabBar.Tab(getString(R.string.shelf), R.drawable.ic_tab_shelf),
                    ShelfTabBar.Tab("分组", R.drawable.ic_tab_groups),
                    ShelfTabBar.Tab("喜爱", R.drawable.ic_tab_fav),
                    ShelfTabBar.Tab(getString(R.string.profile_tab), R.drawable.ic_tab_profile)
                )
            )
            onTabSelected = { index -> switchTab(Tab.entries[index]) }
        }
        setupEmpty()
        setupProfile()
        GlassStyle.load(this).applyTo(root)

        adapter = BookshelfAdapter(
            layoutMode,
            onClick = { book -> if (!adapter.selecting) open(book) },
            onLongClick = { book -> if (!adapter.selecting) startSelecting(book) },
            onFavorite = { book -> toggleFavorite(book) },
            onFolderClick = { group ->
                if (adapter.selecting) {
                    // 选择模式下点文件夹不跳转
                } else if (group == null) {
                    addGroupDialog()
                } else {
                    openFolder(group)
                }
            },
            onFolderLongClick = { group -> if (!adapter.selecting) groupMenu(group) }
        )
        adapter.onSelectionChanged = { count -> updateSelectionUi(count) }
        recycler.adapter = adapter
        recycler.addOnItemTouchListener(ShelfDragSelection(recycler, adapter))
        applyLayoutMode()

        findViewById<View>(R.id.btnImport).setGlassClickListener { showAddMenu(it) }
        findViewById<GlassActionButton>(R.id.btnSearch).apply {
            text = ""
            textView.visibility = View.GONE
            val icon = android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_header_search)
                setColorFilter(getColor(R.color.shelf_title))
            }
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(22), dp(22), android.view.Gravity.CENTER))
            setGlassClickListener { startActivity(SearchActivity.intent(this@MainActivity)) }
        }
        findViewById<View>(R.id.btnCancelSelect).setGlassClickListener { exitSelecting() }
        findViewById<View>(R.id.btnSelectAll).setGlassClickListener { toggleSelectAll() }
        findViewById<View>(R.id.actGroup).setGlassClickListener(actionSheet) { showGroupPicker() }
        findViewById<View>(R.id.actPin).setGlassClickListener(actionSheet) { pinSelected() }
        findViewById<View>(R.id.actRemove).setGlassClickListener(actionSheet) { deleteSelected() }
        btnMode.setGlassClickListener { toggleLayout() }
        btnFormat.setGlassClickListener { showFormatMenu() }
        btnBack.setGlassClickListener { closeFolder() }

        recycler.post {
            recycler.setPadding(dp(4), dp(6), dp(4), 0)
            refreshBackdrop()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    findViewById<View>(R.id.menuPanel).visibility == View.VISIBLE -> hideHeaderMenu()
                    adapter.selecting -> exitSelecting()
                    currentGroup != null -> closeFolder()
                    currentTab == Tab.PROFILE -> switchTab(Tab.SHELF)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
        openedBookId?.let { id ->
            val position = books.indexOfFirst { it.id == id }
            if (position >= 0) recycler.scrollToPosition(position)
            openedBookId = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("opened_book_id", openedBookId)
        super.onSaveInstanceState(outState)
    }

    private fun prefs() = getSharedPreferences("shelf", MODE_PRIVATE)

    private fun showFormatMenu() {
        val items = ShelfFormat.entries.map { format ->
            getString(format.label) to {
                if (format != shelfFormat) {
                    shelfFormat = format
                    prefs().edit().putString("shelf_format", format.key).apply()
                    reload()
                    if (books.isNotEmpty()) recycler.scrollToPosition(0)
                }
            }
        }
        showHeaderMenu(btnFormat, items, selectedIndex = shelfFormat.ordinal)
    }

    private val tabNames = listOf("书架", "分组", "喜爱", "我的")

    private fun setupRecycler() {
        val container = findViewById<FrameLayout>(R.id.container)
        recycler = RecyclerView(this).also { rv ->
            rv.setPadding(dp(4), dp(6), dp(4), dp(12))
            rv.clipToPadding = false
            rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) refreshBackdrop()
                }
            })
            container.addView(rv, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun setupEmpty() {
        empty = TextView(this).apply {
            text = getString(R.string.empty_shelf)
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.ink_soft))
            textSize = 14f
            visibility = View.GONE
        }
        findViewById<FrameLayout>(R.id.container).addView(
            empty, FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 114).coerceIn(4, 12)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode()
        recycler.post { refreshBackdrop() }
    }

    private fun applyLayoutMode() {
        if (layoutMode == ShelfLayout.LIST) {
            recycler.layoutManager = LinearLayoutManager(this)
            btnMode.text = "▦"
        } else {
            recycler.layoutManager = GridLayoutManager(this, gridSpanCount())
            btnMode.text = "☰"
        }
        adapter.setLayout(layoutMode)
    }

    private fun toggleLayout() {
        layoutMode = if (layoutMode == ShelfLayout.GRID) ShelfLayout.LIST else ShelfLayout.GRID
        prefs().edit().putString(
            "shelf_layout", if (layoutMode == ShelfLayout.LIST) "list" else "grid"
        ).apply()
        applyLayoutMode()
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab && currentGroup == null) return
        hideHeaderMenu()
        val previous = currentTab
        currentTab = tab
        currentGroup = null
        if (adapter.selecting) exitSelecting() else reload()
        val content = findViewById<View>(R.id.container)
        if (previous == Tab.PROFILE || tab == Tab.PROFILE) {
            content.animatePageEntry(forward = tab == Tab.PROFILE)
        } else {
            content.animate().cancel()
            content.translationX = 0f
            content.alpha = 1f
        }
    }

    private fun updateTabBar() {
        findViewById<ShelfTabBar>(R.id.bottomBar).selectedIndex = currentTab.ordinal
    }

    private fun refreshBackdrop() {
        bottomBarView.invalidate()
    }

    private fun reload() {
        groups.clear()
        groups.addAll(GroupStore.load(this))
        val all = BookStore.load(this)
        val profileHome = currentTab == Tab.PROFILE
        val shelfHome = currentTab == Tab.SHELF && currentGroup == null
        val formatLabel = getString(shelfFormat.label)
        btnFormat.visibility = if (shelfHome) View.VISIBLE else View.GONE
        btnFormat.text = getString(R.string.shelf_format_button, formatLabel)
        btnFormat.contentDescription = getString(R.string.shelf_format_description, formatLabel)
        profilePage.visibility = if (profileHome) View.VISIBLE else View.GONE
        recycler.visibility = if (profileHome) View.GONE else View.VISIBLE
        actions.visibility = if (currentTab == Tab.PROFILE || adapter.selecting) View.GONE else View.VISIBLE
        if (profileHome) {
            profilePage.findViewById<TextView>(R.id.profileRecentDetail).text =
                all.filter { it.lastRead > 0L }.maxByOrNull { it.lastRead }?.title ?: getString(R.string.profile_no_recent)
            profilePage.findViewById<TextView>(R.id.profileStatsDetail).text = readingDurationLabel()
        }

        val order = compareByDescending<Book> { it.pinned }.thenByDescending { it.lastRead }
        val folderList = mutableListOf<BookshelfAdapter.Folder>()
        val visible: List<Book> = when {
            currentTab == Tab.PROFILE -> emptyList()
            currentTab == Tab.FAVORITE -> all.filter { it.favorite }
            currentGroup != null -> all.filter { it.groupId == currentGroup!!.id }
            currentTab == Tab.GROUPS -> {
                // 分组页只显示文件夹卡片，不显示散书
                val members = all.groupBy { it.groupId }
                groups.forEach { group ->
                    val groupBooks = members[group.id].orEmpty()
                    folderList.add(BookshelfAdapter.Folder(group, groupBooks.sortedWith(order).take(4)))
                }
                emptyList()
            }
            shelfFormat != ShelfFormat.ALL -> all.filter { it.format == shelfFormat.key }
            else -> all
        }

        books.clear()
        books.addAll(visible.sortedWith(order))
        adapter.submit(folderList, books)

        when {
            profileHome -> empty.visibility = View.GONE
            currentTab == Tab.FAVORITE && books.isEmpty() -> {
                empty.text = "还没有喜爱的书\n点卡片右上角的小心心收藏"
                empty.visibility = View.VISIBLE
            }
            currentTab == Tab.GROUPS && books.isEmpty() && folderList.isEmpty() -> {
                empty.text = "还没有分组\n点右上角「＋ → 新建分组」创建一个"
                empty.visibility = View.VISIBLE
            }
            currentGroup != null && books.isEmpty() -> {
                empty.text = "这个分组还没有书"
                empty.visibility = View.VISIBLE
            }
            books.isEmpty() && folderList.isEmpty() -> {
                empty.text = if (shelfHome && shelfFormat != ShelfFormat.ALL) {
                    getString(R.string.empty_shelf_format, formatLabel)
                } else {
                    getString(R.string.empty_shelf)
                }
                empty.visibility = View.VISIBLE
            }
            else -> empty.visibility = View.GONE
        }
        if (adapter.selecting) empty.visibility = View.GONE
        // 列表为空时改采极光层，避免玻璃抓不到内容变黑

        if (currentGroup != null) {
            btnBack.visibility = View.VISIBLE
            title.text = currentGroup!!.name
            bottomBarView.visibility = View.GONE
        } else {
            btnBack.visibility = View.GONE
            title.text = when (currentTab) {
                Tab.PROFILE -> getString(R.string.profile_tab)
                Tab.FAVORITE -> "喜爱"
                Tab.GROUPS -> "分组"
                else -> getString(R.string.shelf)
            }
            bottomBarView.visibility = View.VISIBLE
        }
        updateTabBar()
    }

    private fun setupProfile() {
        val host = findViewById<FrameLayout>(R.id.container)
        profilePage = layoutInflater.inflate(R.layout.view_profile, host, false)
        host.addView(profilePage)
        profilePage.findViewById<View>(R.id.profileRecent).setOnClickListener {
            openProfilePage(ReadingHistoryActivity::class.java)
        }
        profilePage.findViewById<View>(R.id.profileStats).setOnClickListener {
            openProfilePage(ReadingStatsActivity::class.java)
        }
        profilePage.findViewById<View>(R.id.profileAbout).setOnClickListener { showAboutCard() }
        profilePage.findViewById<View>(R.id.profileSettings).setOnClickListener { showProfileSettings() }
    }

    private fun openProfilePage(destination: Class<out AppCompatActivity>) {
        val intent = Intent(this, destination)
        if (Build.VERSION.SDK_INT >= 34) startActivity(intent) else {
            val animation = android.app.ActivityOptions.makeCustomAnimation(this, R.anim.stats_enter, R.anim.stats_under_exit)
            startActivity(intent, animation.toBundle())
        }
    }

    private fun readingDurationLabel(): String {
        val minutes = ReadingStats.total(this) / 60_000
        return if (minutes >= 60) getString(R.string.profile_duration_hours_minutes, minutes / 60, minutes % 60)
        else getString(R.string.profile_duration_minutes, minutes)
    }

    private data class ProfileSetting(
        val title: String,
        val choices: List<String>,
        val selected: () -> Int,
        val apply: (Int) -> Unit
    )

    private fun showAboutCard() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = getString(R.string.profile_about)
        content.findViewById<TextView>(R.id.cardMessage).apply {
            text = getString(R.string.profile_about_details, version)
            visibility = View.VISIBLE
        }
        addCardRow(content, getString(R.string.ok), Color.parseColor("#222222")) {
            dialog.dismiss()
        }
        // 信息型卡片只保留一个确认按钮
        content.findViewById<View>(R.id.cardCancel).visibility = View.GONE
        val rows = content.findViewById<LinearLayout>(R.id.cardRows)
        (rows.getChildAt(rows.childCount - 1) as? View)?.let { okRow ->
            okRow.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x12000000)
            }
            okRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(6)
                bottomMargin = dp(16)
            }
            okRow.findViewById<TextView>(R.id.cardRowText)?.apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
        showCard(dialog, content)
    }
    private fun showProfileSettings() {
        val dialog = ComponentDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        val cardTitle = content.findViewById<TextView>(R.id.cardTitle)
        val rows = content.findViewById<LinearLayout>(R.id.cardRows)
        val back = content.findViewById<GlassActionButton>(R.id.cardBack)
        val backdrop = findViewById<View>(R.id.root)
        val color = getColor(R.color.shelf_title)
        val settings = listOf(
            ProfileSetting("阅读背景", listOf("纸张", "白色", "夜间", "护眼绿"), { ReaderPrefs.theme(this) }) {
                ReaderPrefs.setTheme(this, it)
            },
            ProfileSetting("翻页方式", listOf("无", "平移", "仿真", "淡入"), { ReaderPrefs.turnMode(this) }) {
                ReaderPrefs.setTurnMode(this, it)
            },
            ProfileSetting("书架布局", listOf("网格", "列表"), { layoutMode.ordinal }) {
                if (it != layoutMode.ordinal) toggleLayout()
            },
            ProfileSetting(
                getString(R.string.profile_glass_style),
                GlassStyle.entries.map { getString(it.label) },
                { GlassStyle.load(this).ordinal }
            ) {
                val style = GlassStyle.entries[it]
                style.save(this)
                style.applyTo(backdrop)
                style.applyTo(content.rootView)
            }
        )

        content.findViewById<View>(R.id.cardCancel).visibility = View.GONE
        content.setPadding(0, 0, 0, dp(14))
        rows.minimumHeight = dp(56) * settings.size
        rows.gravity = Gravity.CENTER_VERTICAL
        back.visibility = View.VISIBLE
        back.textView.setPadding(0, 0, 0, 0)
        back.backdropSource = backdrop

        var current: ProfileSetting? = null
        var transitioning = false
        lateinit var navigate: (ProfileSetting?) -> Unit

        fun render(setting: ProfileSetting?) {
            current = setting
            cardTitle.text = setting?.title ?: getString(R.string.profile_settings)
            rows.removeAllViews()
            if (setting == null) {
                settings.forEach { item ->
                    addCardRow(content, item.title, color) { navigate(item) }
                }
            } else {
                var selected = setting.selected()
                setting.choices.forEachIndexed { index, label ->
                    addCardRow(content, label, color, selected = index == selected) { row ->
                        if (transitioning) return@addCardRow
                        if (index != selected) {
                            setting.choices.getOrNull(selected)?.let { previous ->
                                setCardRowSelection(rows.getChildAt(selected), previous, false)
                            }
                            setCardRowSelection(row, label, true)
                            selected = index
                            setting.apply(index)
                        }
                        row.animate().cancel()
                        row.scaleX = 0.96f
                        row.scaleY = 0.94f
                        row.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                }
            }
        }

        navigate = { setting ->
            if (!transitioning) {
                transitioning = true
                val end = if (rows.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                val offset = dp(22).toFloat() * end * if (setting == null) -1 else 1
                rows.animate().alpha(0f).translationX(-offset).setDuration(95).withEndAction {
                    if (dialog.isShowing) {
                        render(setting)
                        rows.translationX = offset
                        rows.animate().alpha(1f).translationX(0f).setDuration(175)
                            .withEndAction { transitioning = false }.start()
                    }
                }.start()
            }
        }

        fun goBack() {
            if (!transitioning) {
                if (current == null) dialog.dismiss() else navigate(null)
            }
        }

        back.setGlassClickListener { goBack() }
        dialog.onBackPressedDispatcher.addCallback(dialog) { goBack() }
        dialog.setCanceledOnTouchOutside(true)
        render(null)
        showCard(dialog, content, R.style.SettingsCardAnimation)
    }

    private fun openFolder(group: BookGroup) {
        currentGroup = group
        animateFolderSwitch(entering = true)
    }

    private fun closeFolder() {
        if (currentGroup == null) return
        currentGroup = null
        animateFolderSwitch(entering = false)
    }

    private fun animateFolderSwitch(entering: Boolean) {
        val rv = recycler
        val offset = dp(24).toFloat()
        rv.animate().cancel()
        rv.animate().alpha(0f).translationX(if (entering) -offset else offset).setDuration(90)
            .withEndAction {
                reload()
                rv.translationX = if (entering) offset else -offset
                rv.alpha = 0f
                rv.animate().alpha(1f).translationX(0f).setDuration(170).start()
            }.start()
    }

    private fun showCard(dialog: Dialog, content: View, windowAnimations: Int = R.style.SettingsCardAnimation) {
        // 背景交给 AGSL 着色器画，content 只保留很小的白色蒙版保证文字可读
        content.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(0x2EFFFFFF)
        }
        val glass = AgslGlassView(this).apply {
            cornerRadius = dp(26).toFloat()
            blurRadius = dp(11).toFloat()
            tintColor = 0xFFFFFFFF.toInt()
            tintAlpha = 0.10f
            refractionHeight = dp(22).toFloat()
            refractionAmount = dp(30).toFloat()
            dispersion = 0.16f
        }
        glass.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.setContentView(glass)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0f)
        dialog.window?.setWindowAnimations(windowAnimations)
        dialog.show()
        dialog.window?.setLayout(dp(345), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        val backdrop = findViewById<View>(R.id.root)
        glass.backdropSource = backdrop
        listOf(R.id.cardCancel, R.id.btnCancelGroup).forEach { id ->
            content.findViewById<GlassActionButton>(id)?.backdropSource = backdrop
        }
        GlassStyle.load(this).applyTo(glass)
    }

    private fun addCardRow(
        content: View,
        label: String,
        color: Int,
        clickable: Boolean = true,
        selected: Boolean = false,
        onClick: (View) -> Unit = {}
    ) {
        val rows = content.findViewById<LinearLayout>(R.id.cardRows)
        val row = layoutInflater.inflate(R.layout.row_dialog_action, rows, false)
        val text = row.findViewById<TextView>(R.id.cardRowText)
        setCardRowSelection(row, label, selected)
        text.setTextColor(color)
        if (clickable) row.setOnClickListener(onClick) else row.isClickable = false
        rows.addView(row)
    }

    private fun setCardRowSelection(row: View, label: String, selected: Boolean) {
        row.isSelected = selected
        row.findViewById<TextView>(R.id.cardRowText).text =
            if (selected) getString(R.string.checked_choice_label, label) else label
    }

    private fun showGlassChoices(
        title: String,
        message: String?,
        actions: List<Pair<String, Int>>,
        onPick: (Int) -> Unit
    ) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = title
        if (!message.isNullOrEmpty()) {
            val m = content.findViewById<TextView>(R.id.cardMessage)
            m.text = message
            m.visibility = View.VISIBLE
        }
        actions.forEachIndexed { index, (label, color) ->
            addCardRow(content, label, color) {
                dialog.dismiss()
                onPick(index)
            }
        }
        content.findViewById<View>(R.id.cardCancel).setGlassClickListener { dialog.dismiss() }
        showCard(dialog, content)
    }

    private fun groupMenu(group: BookGroup) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = group.name
        addCardRow(content, "重命名分组", Color.parseColor("#222222")) {
            dialog.dismiss()
            renameGroupDialog(group)
        }
        addCardRow(content, "删除分组", Color.parseColor("#C0442E")) {
            dialog.dismiss()
            deleteGroupDialog(group)
        }
        content.findViewById<View>(R.id.cardCancel).setGlassClickListener { dialog.dismiss() }
        showCard(dialog, content)
    }

    private fun addGroupDialog() {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = "新建分组"
        val input = content.findViewById<EditText>(R.id.cardInput)
        input.visibility = View.VISIBLE
        val save = content.findViewById<GlassActionButton>(R.id.cardCancel)
        save.text = "创建"
        save.setGlassClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                GroupStore.add(this, name)
                reload()
            }
            dialog.dismiss()
        }
        showCard(dialog, content)
    }

    private fun renameGroupDialog(group: BookGroup) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = "重命名分组"
        val input = content.findViewById<EditText>(R.id.cardInput)
        input.visibility = View.VISIBLE
        input.setText(group.name)
        input.setSelection(group.name.length)
        val save = content.findViewById<GlassActionButton>(R.id.cardCancel)
        save.text = "保存"
        save.setGlassClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                GroupStore.rename(this, group.id, name)
                if (currentGroup?.id == group.id) currentGroup = group.copy(name = name)
                reload()
            }
            dialog.dismiss()
        }
        showCard(dialog, content)
    }

    private fun deleteGroupDialog(group: BookGroup) {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = "删除「${group.name}」？"
        val rows = content.findViewById<LinearLayout>(R.id.cardRows)

        val memberCount = BookStore.load(this).count { it.groupId == group.id }
        val check = CheckBox(this).apply {
            text = "同时从书架删除"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#8A857D"))
            isChecked = false
        }

        addCardRow(content, "确认删除", Color.parseColor("#C0442E")) {
            if (check.isChecked) {
                BookStore.load(this).filter { it.groupId == group.id }.forEach { BookStore.remove(this, it) }
            }
            GroupStore.remove(this, group.id)
            if (currentGroup?.id == group.id) currentGroup = null
            dialog.setOnDismissListener {
                dialog.setOnDismissListener(null)
                reload()
            }
            dialog.dismiss()
        }
        (rows.getChildAt(rows.childCount - 1) as? View)?.let { confirmRow ->
            confirmRow.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x24C0442E)
            }
            confirmRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(4)
            }
            confirmRow.findViewById<TextView>(R.id.cardRowText)?.apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
        val checkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        checkRow.addView(check)
        rows.addView(
            checkRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(8) }
        )
        content.findViewById<View>(R.id.cardCancel).setGlassClickListener { dialog.dismiss() }
        showCard(dialog, content)
    }
    private fun showGroupPicker() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val all = BookStore.load(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_picker, null)

        val list = content.findViewById<LinearLayout>(R.id.groupList)
        val editor = content.findViewById<View>(R.id.newGroupEditor)
        val newRow = content.findViewById<View>(R.id.newGroupRow)
        val input = content.findViewById<EditText>(R.id.inputGroup)
        val create = content.findViewById<View>(R.id.btnCreateGroup)
        val currentIds = all.filter { it.id in ids }.mapNotNull { it.groupId }.distinct()

        val dialog = Dialog(this)
        fun close() {
            dialog.dismiss()
            exitSelecting()
        }
        fun assign(groupId: String?) {
            BookStore.setGroup(this, ids, groupId)
            reload()
            close()
        }

        content.findViewById<View>(R.id.btnCancelGroup).setGlassClickListener { close() }

        if (groups.isEmpty()) {
            val hint = TextView(this).apply {
                text = "还没有分组"
                setTextColor(getColor(R.color.ink_soft))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            list.addView(hint)
        }
        groups.forEach { g ->
            val row = layoutInflater.inflate(R.layout.row_group_picker, list, false)
            row.findViewById<TextView>(R.id.rowName).text = g.name
            row.findViewById<TextView>(R.id.rowCount).text = "${all.count { it.groupId == g.id }} 本"
            row.findViewById<View>(R.id.rowCheck).visibility =
                if (currentIds.size == 1 && currentIds[0] == g.id) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener { assign(g.id) }
            list.addView(row)
        }

        newRow.setOnClickListener {
            editor.visibility = View.VISIBLE
            input.requestFocus()
        }
        create.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            BookStore.setGroup(this, ids, GroupStore.add(this, name).id)
            reload()
            close()
        }

        showCard(dialog, content)
    }
    private fun toggleFavorite(book: Book) {
        BookStore.setFavorite(this, book.id, !book.favorite)
        reload()
    }

    private fun startSelecting(book: Book?) {
        if (book == null) adapter.startSelecting() else adapter.enterSelection(book)
        bottomBarView.animate().cancel()
        bottomBarView.visibility = View.GONE
        showBottomBar(actionSheet)
        actionSheet.post { updatePinAction() }
        selectionActions.visibility = View.VISIBLE
        actions.visibility = View.GONE
        btnBack.visibility = View.GONE
        title.text = "已选 ${if (book == null) 0 else 1} 本"
        empty.visibility = View.GONE
    }

    private fun exitSelecting() {
        adapter.exitSelection()
        actionSheet.animate().cancel()
        actionSheet.visibility = View.GONE
        selectionActions.visibility = View.GONE
        actions.visibility = View.VISIBLE
        reload()
        if (bottomBarView.visibility == View.VISIBLE) showBottomBar(bottomBarView)
    }

    private fun showBottomBar(bar: View) {
        bar.resetGlassPressMotion()
        bar.animate().cancel()
        bar.alpha = 0f
        bar.translationY = dp(24).toFloat()
        bar.scaleX = 0.94f
        bar.scaleY = 0.94f
        bar.visibility = View.VISIBLE
        bar.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(270)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .setUpdateListener { bar.invalidate() }
            .start()
    }

    private fun updateSelectionUi(count: Int) {
        title.text = if (adapter.selecting) "已选 $count 本" else getString(R.string.shelf)
        findViewById<GlassActionButton>(R.id.btnSelectAll).text =
            if (adapter.allSelected()) "取消全选" else "全选"
        updatePinAction()
    }

    /** 选中项里已经全部置顶时，按钮变成「取消置顶」 */
    private fun selectedAllPinned(): Boolean {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return false
        return ids.all { id -> books.firstOrNull { it.id == id }?.pinned == true }
    }

    private fun updatePinAction() {
        val unpin = selectedAllPinned()
        findViewById<TextView>(R.id.actPin)?.text = if (unpin) "取消置顶" else "置顶"
    }

    private fun pinSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val unpin = selectedAllPinned()
        BookStore.setPinned(this, ids, !unpin)
        Toast.makeText(
            this,
            if (unpin) "已取消置顶 ${ids.size} 本" else "已置顶 ${ids.size} 本",
            Toast.LENGTH_SHORT
        ).show()
        exitSelecting()
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        showGlassChoices(
            "删除 ${ids.size} 本书？",
            "导入的文件会一起删除，无法恢复。",
            listOf("删除" to Color.parseColor("#C0442E"))
        ) {
            ids.forEach { id ->
                books.firstOrNull { it.id == id }?.let { BookStore.remove(this, it) }
            }
            exitSelecting()
        }
    }

    private fun toggleSelectAll() {
        if (adapter.allSelected()) adapter.clearSelection() else adapter.selectAll()
    }

    private fun showAddMenu(anchor: View) {
        showHeaderMenu(anchor, listOf(
            "导入文件" to { filePicker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
            "按目录导入" to { folderPicker.launch(null) },
            "新建分组" to { addGroupDialog() },
            "清理重复书籍" to { cleanupDuplicates() }
        ))
    }

    private fun showHeaderMenu(
        anchor: View,
        items: List<Pair<String, () -> Unit>>,
        selectedIndex: Int = -1
    ) {
        val scrim = findViewById<View>(R.id.menuScrim)
        val panel = findViewById<AgslGlassView>(R.id.menuPanel)
        if (panel.visibility == View.VISIBLE) {
            hideHeaderMenu()
            return
        }
        panel.apply {
            backdropSource = this@MainActivity.findViewById(R.id.shelfBackdrop)
            useControlOptics(24f)
        }
        val isRtl = actions.layoutDirection == View.LAYOUT_DIRECTION_RTL
        panel.updateLayoutParams<FrameLayout.LayoutParams> {
            topMargin = findViewById<View>(R.id.header).bottom + dp(6)
            marginEnd = dp(8) + if (isRtl) anchor.left else actions.width - anchor.right
        }
        val rows = findViewById<LinearLayout>(R.id.menuRows)
        rows.removeAllViews()
        items.forEachIndexed { index, (label, action) ->
            val tv = TextView(this)
            tv.isSelected = index == selectedIndex
            tv.text = if (tv.isSelected) getString(R.string.checked_choice_label, label) else label
            tv.textSize = 13f
            tv.setTextColor(getColor(R.color.shelf_title))
            val v = dp(13)
            tv.setPadding(v, v, v, v)
            tv.isClickable = true
            tv.setGlassClickListener {
                if (scrim.isEnabled) {
                    hideHeaderMenu()
                    action()
                }
            }
            rows.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            if (index != items.lastIndex) {
                val divider = View(this)
                divider.setBackgroundColor(0x1F000000)
                rows.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply { marginStart = dp(13); marginEnd = dp(13) })
            }
        }
        scrim.isEnabled = true
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(120).start()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.80f
        panel.scaleY = 0.80f
        panel.translationY = -dp(20).toFloat()
        panel.post {
            if (panel.visibility != View.VISIBLE || !scrim.isEnabled) return@post
            panel.pivotX = if (isRtl) 0f else panel.width.toFloat()
            panel.pivotY = 0f
            panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .setUpdateListener { panel.invalidate() }
                .start()
        }
        scrim.setOnClickListener { hideHeaderMenu() }
    }

    private fun hideHeaderMenu() {
        val scrim = findViewById<View>(R.id.menuScrim)
        val panel = findViewById<View>(R.id.menuPanel)
        if (panel.visibility != View.VISIBLE || !scrim.isEnabled) return
        scrim.isEnabled = false
        scrim.animate().alpha(0f).setDuration(110).start()
        panel.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .translationY(-dp(12).toFloat())
            .setDuration(180)
            .setUpdateListener { panel.invalidate() }
            .withEndAction {
                panel.visibility = View.GONE
                scrim.visibility = View.GONE
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationY = 0f
            }
            .start()
    }

    private fun open(book: Book) {
        openedBookId = book.id
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("bookId", book.id))
    }

    private fun importUris(files: List<Pair<Uri, String>>) {
        if (importing) {
            Toast.makeText(this, "正在导入，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        Toast.makeText(this, "正在导入 ${files.size} 个文件…", Toast.LENGTH_SHORT).show()
        Thread {
            val added = mutableListOf<Book>()
            var duplicates = 0
            var failed = 0
            files.forEach { (uri, name) ->
                when (val r = runCatching { BookStore.import(this, uri, name) }.getOrNull()) {
                    is ImportResult.Added -> added.add(r.book)
                    ImportResult.Duplicate -> duplicates++
                    else -> failed++
                }
            }
            runOnUiThread {
                importing = false
                reload()
                Toast.makeText(this, importSummary(added.size, duplicates, failed), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun importFolder(treeUri: Uri) {
        if (importing) {
            Toast.makeText(this, "正在导入，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        Toast.makeText(this, "正在扫描目录…", Toast.LENGTH_SHORT).show()
        Thread {
            val directoryName = queryDirectoryName(treeUri)
            val found = mutableListOf<FoundDocument>()
            runCatching { collectDocuments(treeUri, found, 0) }
            if (found.isEmpty()) {
                val path = pathFromTreeUri(treeUri)
                if (path != null && isAllFilesAccess()) {
                    runCatching { scanDir(File(path), found, 0) }
                }
            }
            if (found.isEmpty()) {
                runOnUiThread {
                    importing = false
                    Toast.makeText(this, "目录里没有找到 TXT / EPUB 文件", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            runOnUiThread {
                Toast.makeText(this, "找到 ${found.size} 本，开始导入…", Toast.LENGTH_SHORT).show()
            }
            val added = mutableListOf<Book>()
            val dirOf = mutableMapOf<String, String>()
            var duplicates = 0
            var failed = 0
            found.forEach { doc ->
                when (val r = runCatching { BookStore.import(this, doc.uri, doc.name) }.getOrNull()) {
                    is ImportResult.Added -> {
                        added.add(r.book)
                        dirOf[r.book.id] = doc.dir
                    }
                    ImportResult.Duplicate -> duplicates++
                    else -> failed++
                }
            }
            val grouped = runCatching { groupByDirectory(directoryName, added, dirOf) }.getOrDefault(emptyList())
            val summary = importSummary(added.size, duplicates, failed) + when {
                grouped.isEmpty() -> ""
                grouped.size == 1 -> "，已归入「${grouped.first()}」"
                else -> "，已创建 ${grouped.size} 个分组：" + grouped.take(3).joinToString("、") +
                    if (grouped.size > 3) " 等" else ""
            }
            runOnUiThread {
                importing = false
                reload()
                Toast.makeText(
                    this,
                    summary,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /** 目录层级 → 分组：每层目录一本书只进它自己那一层的分组，不向下递归合并。 */
    private fun groupByDirectory(
        rootName: String,
        added: List<Book>,
        dirOf: Map<String, String>
    ): List<String> {
        if (added.isEmpty()) return emptyList()
        val byDir = added.groupBy { dirOf[it.id].orEmpty() }
        val leafCount = byDir.keys.filter { it.isNotEmpty() }
            .groupingBy { it.substringAfterLast('/') }.eachCount()
        val created = mutableListOf<String>()
        byDir.forEach { (dir, books) ->
            val leaf = if (dir.isEmpty()) rootName else dir.substringAfterLast('/')
            // 不同上级目录下同名子目录时，用完整相对路径区分
            val name = if (dir.isEmpty() || (leafCount[leaf] ?: 0) <= 1) leaf else dir.replace('/', '·')
            runCatching {
                GroupStore.groupImportedBooks(this@MainActivity, name, books.map { it.id })
            }.getOrNull()?.let { created.add(it.name) }
        }
        return created
    }

    private fun cleanupDuplicates() {
        if (importing) {
            Toast.makeText(this, "正在处理，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        Toast.makeText(this, "正在检查重复书籍…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { BookStore.cleanupDuplicates(this) }
            runOnUiThread {
                importing = false
                reload()
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { if (it == 0) "没有发现重复书籍" else "清理了 $it 本重复书籍" },
                        onFailure = { "清理失败，请重试" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun importSummary(added: Int, duplicates: Int, failed: Int): String {
        val sb = StringBuilder("已导入 $added 本")
        if (duplicates > 0) sb.append("，跳过 $duplicates 本重复")
        if (failed > 0) sb.append("，失败 $failed 本")
        return sb.toString()
    }

    private data class FoundDocument(val uri: Uri, val name: String, val dir: String)

    private fun collectDocuments(treeUri: Uri, out: MutableList<FoundDocument>, depth: Int, dir: String = "") {
        if (depth > 8) return
        val docId = if (depth == 0) DocumentsContract.getTreeDocumentId(treeUri)
            else DocumentsContract.getDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childDir = if (dir.isEmpty()) name else "$dir/$name"
                    collectDocuments(childUri, out, depth + 1, childDir)
                } else {
                    val lower = name.lowercase()
                    if (lower.endsWith(".txt") || lower.endsWith(".epub")) {
                        out.add(FoundDocument(childUri, name, dir))
                    }
                }
            }
        }
    }

    private fun pathFromTreeUri(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = id.split(":", limit = 2)
        if (parts.size < 2) return null
        if (parts[0] != "primary") return null
        val rel = parts[1].trim('/')
        return if (rel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
    }

    private fun queryDirectoryName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return "导入目录"
        val name = queryName(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
        return name?.takeIf { it.isNotBlank() }
            ?: id.substringAfter(':').trimEnd('/').substringAfterLast('/').ifBlank { "导入目录" }
    }

    private fun scanDir(dir: File, out: MutableList<FoundDocument>, depth: Int, rel: String = "") {
        if (depth > 8) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (f.isDirectory) {
                val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                scanDir(f, out, depth + 1, childRel)
            } else {
                val lower = f.name.lowercase()
                if (lower.endsWith(".txt") || lower.endsWith(".epub")) {
                    out.add(FoundDocument(Uri.fromFile(f), f.name, rel))
                }
            }
        }
    }

    private fun isAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun ensureAllFilesAccess(): Boolean {
        if (isAllFilesAccess()) return true
        Toast.makeText(
            this,
            "按目录导入需要「所有文件访问权限」，请在设置里开启后重试",
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        return false
    }

    private fun queryName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

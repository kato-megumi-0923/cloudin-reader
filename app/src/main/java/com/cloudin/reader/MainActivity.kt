package com.cloudin.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class Tab { SHELF, GROUPS, FAVORITE }

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: BookshelfAdapter
    private lateinit var title: TextView
    private lateinit var btnBack: TextView
    private lateinit var actionSheet: View
    private lateinit var selectionActions: View
    private lateinit var actions: View
    private lateinit var btnMode: TextView
    private lateinit var bottomBarView: View
    private var tabBar: com.example.liquidglass.LiquidGlassTabBar? = null
    private val tabLabels = mutableListOf<TextView>()

    private val books = mutableListOf<Book>()
    private val groups = mutableListOf<BookGroup>()
    private var importing = false
    private var layoutMode = ShelfLayout.GRID
    private var currentTab = Tab.SHELF
    private var currentGroup: BookGroup? = null
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
            barLp.bottomMargin = dp(16) + bars.bottom
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
        bottomBarView = findViewById(R.id.bottomBar)
        tabBar = null

        layoutMode = if (prefs().getString("shelf_layout", "grid") == "list")
            ShelfLayout.LIST else ShelfLayout.GRID

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
        listOf(
            R.id.barMode, R.id.barSelect, R.id.barImport,
            R.id.barSelectAll, R.id.barSelectCancel
        ).mapNotNull { findViewById<View>(it) as? com.example.liquidglass.LiquidGlassView }
            .forEach { glass ->
                glass.backdropSource = findViewById(R.id.bgLayer)
                glass.enableDynamicBackground = true
                glass.blurAmount = 0.42f
                glass.saturation = 160f
                glass.material = com.example.liquidglass.GlassMaterial.REGULAR
                glass.cornerRadius = 999f
                glass.elevation = 0f
            }
        listOf(
            R.id.btnMode, R.id.btnSelect, R.id.btnImport,
            R.id.btnSelectAll, R.id.btnCancelSelect
        ).mapNotNull { findViewById<TextView>(it) }.forEach { it.setTextColor(Color.parseColor("#2E2E2E")) }
        tabBar = null
        val barGlass = findViewById<View>(R.id.bottomBar) as? com.example.liquidglass.LiquidGlassTabBar
        barGlass?.backdropSource = findViewById<View>(R.id.root)
        barGlass?.apply {
            enableDynamicBackground = true
            material = com.example.liquidglass.GlassMaterial.REGULAR
            blurAmount = 0.12f
            adaptiveLensScale = true
            refractionHeight = dp(12).toFloat()
            bevelWidth = dp(24).toFloat()
            refractionFalloff = 2f
            dispersionStrength = 0.12f
            enableSensorHighlight = true
            selectedTintColor = Color.WHITE
            setTabs(
                listOf(
                    com.example.liquidglass.LiquidGlassTabBar.TabItem("书架", getDrawable(R.drawable.ic_tab_shelf)),
                    com.example.liquidglass.LiquidGlassTabBar.TabItem("分组", getDrawable(R.drawable.ic_tab_groups)),
                    com.example.liquidglass.LiquidGlassTabBar.TabItem("喜爱", getDrawable(R.drawable.ic_tab_fav))
                )
            )
            selectedIndex = 0
            onTabSelected = { index ->
                switchTab(
                    when (index) {
                        0 -> Tab.SHELF
                        1 -> Tab.GROUPS
                        else -> Tab.FAVORITE
                    }
                )
            }
        }
        (findViewById<View>(R.id.actionSheet) as? com.example.liquidglass.LiquidGlassView)?.apply {
            backdropSource = findViewById<View>(R.id.root)
            enableDynamicBackground = true
            blurAmount = 0.12f
            adaptiveLensScale = true
            refractionHeight = dp(12).toFloat()
            bevelWidth = dp(24).toFloat()
            dispersionStrength = 0.12f
            glassAppearanceListener = { _ -> applyGlassForeground() }
        }
        findViewById<View>(R.id.root).post { updateTabBar() }
        setupEmpty()

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
        applyLayoutMode()
        applyGlassForeground()

        findViewById<View>(R.id.btnImport).setOnClickListener { showAddMenu() }
        findViewById<View>(R.id.btnSelect).setOnClickListener { startSelecting(null) }
        findViewById<View>(R.id.btnCancelSelect).setOnClickListener { exitSelecting() }
        findViewById<View>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }
        findViewById<View>(R.id.actGroup).setOnClickListener { showGroupPicker() }
        findViewById<View>(R.id.actPin).setOnClickListener { pinSelected() }
        findViewById<View>(R.id.actRemove).setOnClickListener { deleteSelected() }
        btnMode.setOnClickListener { toggleLayout() }
        btnBack.setOnClickListener { closeFolder() }

        (findViewById<View>(R.id.bottomBar) as? com.example.liquidglass.LiquidGlassView)?.apply {
            enableDynamicBackground = true
            post {
                enableShadow = false
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                invalidate()
            }
            blurAmount = 0.1f
            saturation = 160f
            dispersionStrength = 0.12f
        }
        recycler.post {
            recycler.setPadding(dp(4), dp(6), dp(4), 0)
            refreshBackdrop()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    adapter.selecting -> exitSelecting()
                    currentGroup != null -> closeFolder()
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
    }

    private fun prefs() = getSharedPreferences("shelf", MODE_PRIVATE)

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
        applyGlassForeground()
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
        applyGlassForeground()
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab && currentGroup == null) return
        currentTab = tab
        currentGroup = null
        if (adapter.selecting) exitSelecting()
        reload()
    }

    private fun applyGlassForeground() {
        val bar = findViewById<View>(R.id.bottomBar) as? com.example.liquidglass.LiquidGlassView
        val sheet = findViewById<View>(R.id.actionSheet) as? com.example.liquidglass.LiquidGlassView
        val activeGlass = if (actionSheet.visibility == View.VISIBLE) sheet else bar
        val overLight = activeGlass?.isOverLightBackground ?: false
        val ink = if (overLight) Color.parseColor("#2E2E2E") else Color.WHITE
        val remove = if (overLight) Color.parseColor("#C0442E") else Color.parseColor("#FF8A73")
        listOf(R.id.actPinLabel, R.id.actGroupLabel)
            .mapNotNull { findViewById<TextView>(it) }.forEach { it.setTextColor(ink) }
        findViewById<TextView>(R.id.actRemoveLabel)?.setTextColor(remove)
        listOf(R.id.actPin, R.id.actGroup).forEach { id ->
            (findViewById<android.view.ViewGroup>(id)?.getChildAt(0) as? ImageView)?.imageTintList =
                android.content.res.ColorStateList.valueOf(ink)
        }
        findViewById<ImageView>(R.id.actRemoveIcon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(remove)
    }
    private fun updateTabBar() {
        val index = when (currentTab) {
            Tab.SHELF -> 0
            Tab.GROUPS -> 1
            else -> 2
        }
        (findViewById<View>(R.id.bottomBar) as? com.example.liquidglass.LiquidGlassTabBar)?.selectedIndex = index
    }
    private fun refreshBackdrop() {
        val bar = findViewById<View>(R.id.bottomBar) as? GlassBar
        if (bar == null) {
            (findViewById<View>(R.id.bottomBar) as? com.example.liquidglass.LiquidGlassView)?.invalidate()
        }
        if (currentGroup != null || adapter.selecting) return
        recycler.post {
            bar?.captureFrom(recycler)
        }
    }

    private fun reload() {
        groups.clear()
        groups.addAll(GroupStore.load(this))
        val all = BookStore.load(this)

        val folderList = mutableListOf<Pair<BookGroup?, Int>>()
        val visible: List<Book> = when {
            currentTab == Tab.FAVORITE -> all.filter { it.favorite }
            currentGroup != null -> all.filter { it.groupId == currentGroup!!.id }
            currentTab == Tab.GROUPS -> {
                // 分组页只显示文件夹卡片，不显示散书
                groups.forEach { g -> folderList.add(g to all.count { it.groupId == g.id }) }
                emptyList()
            }
            else -> all
        }

        books.clear()
        books.addAll(visible.sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.lastRead }))
        adapter.submit(folderList, books)

        when {
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
                empty.text = getString(R.string.empty_shelf)
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
                Tab.FAVORITE -> "喜爱"
                Tab.GROUPS -> "分组"
                else -> getString(R.string.shelf)
            }
            bottomBarView.visibility = View.VISIBLE
        }
        updateTabBar()
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

    private fun showCard(dialog: Dialog, content: View) {
        content.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(0x8CFFFFFF.toInt())
        }
        val glass = com.example.liquidglass.LiquidGlassView(this)
        glass.cornerRadius = dp(26).toFloat()
        glass.blurAmount = 0.42f
        glass.material = com.example.liquidglass.GlassMaterial.REGULAR

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
        dialog.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.setLayout(dp(345), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        glass.backdropSource = findViewById(R.id.root)
        glass.enableDynamicBackground = true
    }

    private fun addCardRow(
        content: View,
        label: String,
        color: Int,
        clickable: Boolean = true,
        onClick: () -> Unit = {}
    ) {
        val rows = content.findViewById<LinearLayout>(R.id.cardRows)
        val row = layoutInflater.inflate(R.layout.row_dialog_action, rows, false)
        val text = row.findViewById<TextView>(R.id.cardRowText)
        text.text = label
        text.setTextColor(color)
        if (clickable) row.setOnClickListener { onClick() } else row.isClickable = false
        rows.addView(row)
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
        actions.forEachIndexed { index, pair ->
            addCardRow(content, pair.first, pair.second) {
                dialog.dismiss()
                onPick(index)
            }
        }
        content.findViewById<View>(R.id.cardCancel).setOnClickListener { dialog.dismiss() }
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
        content.findViewById<View>(R.id.cardCancel).setOnClickListener { dialog.dismiss() }
        showCard(dialog, content)
    }

    private fun addGroupDialog() {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_group_card, null)
        content.findViewById<TextView>(R.id.cardTitle).text = "新建分组"
        val input = content.findViewById<EditText>(R.id.cardInput)
        input.visibility = View.VISIBLE
        val save = content.findViewById<TextView>(R.id.cardCancel)
        save.text = "创建"
        save.setOnClickListener {
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
        val save = content.findViewById<TextView>(R.id.cardCancel)
        save.text = "保存"
        save.setOnClickListener {
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
        addCardRow(content, "书籍会回到书架，不会被删除", Color.parseColor("#999999"), clickable = false)
        addCardRow(content, "确认删除", Color.parseColor("#C0442E")) {
            GroupStore.remove(this, group.id)
            if (currentGroup?.id == group.id) currentGroup = null
            reload()
            dialog.dismiss()
        }
        content.findViewById<View>(R.id.cardCancel).setOnClickListener { dialog.dismiss() }
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

        content.findViewById<View>(R.id.btnCancelGroup).setOnClickListener { close() }

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

        content.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(0x8CFFFFFF.toInt())
        }
        val glass = com.example.liquidglass.LiquidGlassView(this)
        glass.cornerRadius = dp(26).toFloat()
        glass.blurAmount = 0.42f
        glass.material = com.example.liquidglass.GlassMaterial.REGULAR

        glass.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(glass)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0f)
        dialog.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.setLayout(dp(345), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        glass.backdropSource = findViewById(R.id.root)
        glass.enableDynamicBackground = true
    }
    private fun toggleFavorite(book: Book) {
        BookStore.setFavorite(this, book.id, !book.favorite)
        reload()
    }

    private fun startSelecting(book: Book?) {
        if (book == null) adapter.startSelecting() else adapter.enterSelection(book)
        bottomBarView.visibility = View.GONE
        actionSheet.visibility = View.VISIBLE
        selectionActions.visibility = View.VISIBLE
        actions.visibility = View.GONE
        btnBack.visibility = View.GONE
        title.text = "已选 ${if (book == null) 0 else 1} 本"
        empty.visibility = View.GONE
    }

    private fun exitSelecting() {
        adapter.exitSelection()
        actionSheet.visibility = View.GONE
        bottomBarView.visibility = View.VISIBLE
        selectionActions.visibility = View.GONE
        actions.visibility = View.VISIBLE
        reload()
    }

    private fun updateSelectionUi(count: Int) {
        title.text = if (adapter.selecting) "已选 $count 本" else getString(R.string.shelf)
        findViewById<TextView>(R.id.btnSelectAll).text =
            if (adapter.allSelected()) "取消全选" else "全选"
    }

    private fun pinSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val list = BookStore.load(this)
        list.filter { it.id in ids }.forEach { it.pinned = true }
        BookStore.save(this, list)
        Toast.makeText(this, "已置顶 ${ids.size} 本", Toast.LENGTH_SHORT).show()
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

    private fun showAddMenu() {
        val scrim = findViewById<View>(R.id.menuScrim)
        val panel = findViewById<View>(R.id.menuPanel)
        (panel as? com.example.liquidglass.LiquidGlassView)?.backdropSource = findViewById<View>(R.id.bgLayer)
        (panel as? com.example.liquidglass.LiquidGlassView)?.apply {
            enableDynamicBackground = true
            cornerRadius = dp(24).toFloat()
            blurAmount = 0.14f
        }
        val rows = findViewById<LinearLayout>(R.id.menuRows)
        if (panel.visibility == View.VISIBLE) {
            hideAddMenu()
            return
        }
        rows.removeAllViews()
        val items = listOf(
            "导入文件" to { filePicker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
            "按目录导入" to { if (ensureAllFilesAccess()) folderPicker.launch(null) },
            "新建分组" to { addGroupDialog() },
            "清理重复书籍" to { cleanupDuplicates() }
        )
        items.forEachIndexed { index, pair ->
            val tv = TextView(this)
            tv.text = pair.first
            tv.textSize = 13f
            tv.setTextColor(getColor(R.color.shelf_title))
            val v = dp(13)
            tv.setPadding(v, v, v, v)
            tv.isClickable = true
            tv.setOnClickListener {
                hideAddMenu()
                pair.second()
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
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(120).start()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.88f
        panel.scaleY = 0.88f
        panel.translationY = -dp(10).toFloat()
        panel.post {
            panel.pivotX = panel.width.toFloat()
            panel.pivotY = 0f
            panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
        scrim.setOnClickListener { hideAddMenu() }
    }

    private fun hideAddMenu() {
        val scrim = findViewById<View>(R.id.menuScrim)
        val panel = findViewById<View>(R.id.menuPanel)
        if (panel.visibility != View.VISIBLE) return
        scrim.animate().alpha(0f).setDuration(110).start()
        panel.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(130)
            .withEndAction {
                panel.visibility = View.GONE
                scrim.visibility = View.GONE
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
            }
            .start()
    }

    private fun open(book: Book) {
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
            val list = BookStore.load(this)
            list.addAll(added)
            BookStore.save(this, list)
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
            val found = mutableListOf<Pair<Uri, String>>()
            runCatching { collectDocuments(treeUri, found, 0) }
            if (found.isEmpty()) {
                val path = pathFromTreeUri(treeUri)
                if (path != null && isAllFilesAccess()) {
                    val files = mutableListOf<File>()
                    runCatching { scanDir(File(path), files, 0) }
                    files.forEach { found.add(Uri.fromFile(it) to it.name) }
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
            var duplicates = 0
            var failed = 0
            found.forEach { (uri, name) ->
                when (val r = runCatching { BookStore.import(this, uri, name) }.getOrNull()) {
                    is ImportResult.Added -> added.add(r.book)
                    ImportResult.Duplicate -> duplicates++
                    else -> failed++
                }
            }
            val list = BookStore.load(this)
            list.addAll(added)
            BookStore.save(this, list)
            runOnUiThread {
                importing = false
                reload()
                Toast.makeText(
                    this,
                    importSummary(added.size, duplicates, failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun cleanupDuplicates() {
        if (importing) {
            Toast.makeText(this, "正在处理，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        importing = true
        Toast.makeText(this, "正在检查重复书籍…", Toast.LENGTH_SHORT).show()
        Thread {
            val list = BookStore.load(this)
            val byHash = mutableMapOf<String, MutableList<Book>>()
            list.forEach { b ->
                if (b.hash == null) b.hash = BookStore.bookHash(this, b)
                val h = b.hash ?: return@forEach
                byHash.getOrPut(h) { mutableListOf() }.add(b)
            }
            val toRemove = mutableListOf<Book>()
            byHash.values.forEach { group ->
                if (group.size > 1) {
                    val keep = group.maxByOrNull { it.lastRead } ?: group.first()
                    group.filter { it.id != keep.id }.forEach { toRemove.add(it) }
                }
            }
            val keep = list.filter { b -> toRemove.none { it.id == b.id } }
            BookStore.save(this, keep)
            toRemove.forEach { BookStore.remove(this, it) }
            runOnUiThread {
                importing = false
                reload()
                Toast.makeText(
                    this,
                    if (toRemove.isEmpty()) "没有发现重复书籍" else "清理了 ${toRemove.size} 本重复书籍",
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

    private fun collectDocuments(treeUri: Uri, out: MutableList<Pair<Uri, String>>, depth: Int) {
        if (depth > 8) return
        val docId = DocumentsContract.getDocumentId(treeUri)
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
                    collectDocuments(childUri, out, depth + 1)
                } else {
                    val lower = name.lowercase()
                    if (lower.endsWith(".txt") || lower.endsWith(".epub")) {
                        out.add(childUri to name)
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

    private fun scanDir(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > 8) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            if (f.isDirectory) {
                scanDir(f, out, depth + 1)
            } else {
                val lower = f.name.lowercase()
                if (lower.endsWith(".txt") || lower.endsWith(".epub")) out.add(f)
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

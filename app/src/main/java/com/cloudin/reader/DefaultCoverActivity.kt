package com.cloudin.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 默认封面管理。
 *
 * 网格里只放封面图，不显示编号；长按进入删除模式，支持单选、多选、全选与批量删除，
 * 删除前要过一道二次确认。删掉的封面会让用到它的书重新分到还在池里的封面上。
 */
class DefaultCoverActivity : BaseActivity() {

    override val systemBarDark: Boolean get() = SkinStore.load(this).dark

    private lateinit var root: FrameLayout
    /** 背景 + 封面网格：玻璃按钮的采样源，不含顶栏自身，避免 RenderNode 递归。 */
    private lateinit var contentLayer: FrameLayout
    private lateinit var backdrop: View
    private lateinit var header: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: GlassActionButton
    private lateinit var selectAllButton: GlassActionButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CoverAdapter
    private lateinit var selectionBar: View
    private lateinit var selectionLabel: TextView
    private lateinit var deleteButton: TextView

    private var selecting = false
    private val selected = linkedSetOf<Int>()
    private var pool: CoverPool = CoverPool()
    private var editingDisabled = false
    private var previewLayer: View? = null
    private var cellSize = 0

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addCover(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skin = SkinStore.load(this)

        root = FrameLayout(this)
        // 与书架页 shelfBackdrop 同构：背景和网格放同一层，供玻璃按钮采样；
        // 顶栏单独挂在 root 上，不在这层里，避免玻璃录制时把自己卷进去递归。
        contentLayer = FrameLayout(this)
        backdrop = View(this)
        SkinStore.applyTo(this, backdrop, skin)
        contentLayer.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        adapter = CoverAdapter()
        recycler = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(readerDp(24), 0, readerDp(24), readerDp(96))
            layoutManager = GridLayoutManager(this@DefaultCoverActivity, spanCount())
            this.adapter = this@DefaultCoverActivity.adapter
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val span = spanCount()
                    (layoutManager as? GridLayoutManager)?.spanCount = span
                    cellSize = ((right - left - paddingLeft - paddingRight) / span).coerceAtLeast(1)
                }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateHeaderTitle()
            })
        }
        contentLayer.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentLayer, FrameLayout.LayoutParams(-1, -1))

        // 顶栏挂在内容层之上：网格 clipToPadding=false，滚动时封面会画进顶栏的 padding 区域，
        // 顺序反了就会把返回按钮盖住（看不见也点不到）。与 MainActivity / SkinActivity 一致。
        header = buildHeader(skin.dark)
        root.addView(header, FrameLayout.LayoutParams(-1, readerDp(58)))

        selectionBar = buildSelectionBar(skin.dark)
        root.addView(selectionBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = readerDp(26)
        })
        selectionBar.visibility = View.GONE

        setContentView(root)
        onSystemBarInsets(root) { _, top, _, bottom ->
            val headerHeight = readerDp(58) + top
            (header.layoutParams as FrameLayout.LayoutParams).height = headerHeight
            header.setPadding(0, top, 0, 0)
            recycler.setPadding(readerDp(24), headerHeight, readerDp(24), readerDp(96) + bottom)
            (selectionBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = readerDp(26) + bottom
        }
        ViewCompat.requestApplyInsets(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    previewLayer != null -> dismissPreview()
                    selecting -> exitSelection()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        reloadPool()
        GlassStyle.load(this).applyTo(root)
        SkinTheme.applyGlassEdge(root, skin.pale)
        updateHeaderTitle()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    // ---------------------------------------------------------------- 头部与选择栏

    private fun buildHeader(dark: Boolean): FrameLayout {
        val bar = FrameLayout(this)
        val ink = SkinTheme.title(dark)
        backButton = GlassActionButton(this).apply {
            backdropSource = contentLayer
            // 不用 usePopupOptics()：它把折射压到 3/2dp 且关掉边缘高光，
            // 书架页的返回按钮用默认 control optics（7/9dp + 高光 + 9dp 模糊），这里保持一致。
            useBackIcon(ink)
            setGlassClickListener {
                if (previewLayer != null) dismissPreview() else if (selecting) exitSelection() else finish()
            }
        }
        bar.addView(backButton, FrameLayout.LayoutParams(readerDp(44), readerDp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = readerDp(12)
        })
        titleView = TextView(this).apply {
            setText("默认封面")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ink)
        }
        bar.addView(titleView, FrameLayout.LayoutParams(-1, -1).apply {
            marginStart = readerDp(60)
            marginEnd = readerDp(60)
        })
        selectAllButton = GlassActionButton(this).apply {
            text = "全选"
            textView.textSize = 13f
            textView.setTextColor(ink)
            backdropSource = contentLayer
            // 与返回按钮同款 control optics，不额外降级折射。
            visibility = View.GONE
            setGlassClickListener { toggleSelectAll() }
        }
        bar.addView(selectAllButton, FrameLayout.LayoutParams(-2, readerDp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = readerDp(12)
        })
        return bar
    }

    private fun buildSelectionBar(dark: Boolean): View {
        // 浮动操作条用实心胶囊：玻璃上的小字在照片/深色皮肤下容易糊。
        // 直接用 LinearLayout 当胶囊本体，避免「wrap 容器 + match_parent 子 View」被撑满全屏。
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(readerDp(20), readerDp(7), readerDp(8), readerDp(7))
            background = GradientDrawable().apply {
                cornerRadius = readerDp(26).toFloat()
                setColor(if (dark) 0xF21A1D24.toInt() else 0xF2FFFFFF.toInt())
                setStroke(readerDp(1), if (dark) 0x33FFFFFF else 0x66FFFFFF)
            }
        }
        selectionLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(SkinTheme.title(dark))
        }
        bar.addView(selectionLabel, android.widget.LinearLayout.LayoutParams(-2, -2))
        deleteButton = TextView(this).apply {
            text = "删除"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#C0442E"))
            setPadding(readerDp(16), readerDp(8), readerDp(16), readerDp(8))
            background = GradientDrawable().apply {
                cornerRadius = readerDp(18).toFloat()
                setColor(0xE6FFFFFF.toInt())
            }
            setOnClickListener { if (selected.isNotEmpty()) showDeleteDialog() }
        }
        bar.addView(deleteButton, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = readerDp(16)
        })
        return bar
    }

    // ---------------------------------------------------------------- 数据

    private fun reloadPool() {
        val loaded = DefaultCoverStore.load(this)
        if (loaded == null) {
            editingDisabled = true
            pool = CoverPool()
            toast("封面配置读取失败，暂时不能修改")
        } else {
            editingDisabled = false
            pool = loaded
        }
        adapter.submit(pool.enabled())
        updateSelectionBar()
    }

    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 150).coerceIn(3, 7)
    }

    private fun enterSelection(index: Int) {
        if (editingDisabled) return
        selecting = true
        selected.clear()
        selected.add(index)
        applySelectionState()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        applySelectionState()
    }

    private fun toggleIndex(index: Int) {
        if (!selected.remove(index)) selected.add(index)
        if (selected.isEmpty()) {
            selecting = false
            applySelectionState()
            return
        }
        // 只刷新被点的那一格：整表 notifyDataSetChanged 会让所有封面重绑，看起来闪一下
        adapter.selection = selected.toSet()
        adapter.notifyItemChanged(adapter.positionOf(index))
        updateSelectionBar()
    }

    private fun toggleSelectAll() {
        if (!selecting) return
        val all = pool.enabled()
        if (selected.size == all.size) selected.clear() else {
            selected.clear()
            selected.addAll(all)
        }
        if (selected.isEmpty()) selecting = false
        applySelectionState()
    }

    private fun applySelectionState() {
        adapter.selection = if (selecting) selected.toSet() else emptySet()
        adapter.selecting = selecting
        adapter.notifyDataSetChanged()
        if (selecting && selectAllButton.visibility != View.VISIBLE) {
            selectAllButton.alpha = 0f
            selectAllButton.visibility = View.VISIBLE
            selectAllButton.animate().alpha(1f).setDuration(160).start()
        } else if (!selecting && selectAllButton.visibility == View.VISIBLE) {
            selectAllButton.animate().alpha(0f).setDuration(120)
                .withEndAction { if (!selecting) selectAllButton.visibility = View.GONE }.start()
        }
        selectAllButton.text = if (selecting && selected.size == pool.enabled().size) "取消全选" else "全选"
        updateSelectionBar()
        updateHeaderTitle()
    }

    private fun updateSelectionBar() {
        if (selecting) {
            if (selectionBar.visibility != View.VISIBLE) {
                selectionBar.animate().cancel()
                selectionBar.alpha = 0f
                selectionBar.translationY = readerDp(18).toFloat()
                selectionBar.visibility = View.VISIBLE
                selectionBar.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            }
        } else if (selectionBar.visibility == View.VISIBLE) {
            selectionBar.animate().cancel()
            selectionBar.animate().alpha(0f).translationY(readerDp(12).toFloat()).setDuration(140)
                .withEndAction { if (!selecting) selectionBar.visibility = View.GONE }
                .start()
        }
        selectionLabel.text = "已选 ${selected.size} 张"
        deleteButton.alpha = if (selected.isEmpty()) 0.45f else 1f
        deleteButton.isEnabled = selected.isNotEmpty()
    }

    /** 与书架页一致：上拉时标题随滚动淡出，超过 32dp 后完全隐藏；删除模式不淡出。 */
    private fun updateHeaderTitle() {
        val fadeDistance = readerDp(32).coerceAtLeast(1)
        val manager = recycler.layoutManager as? GridLayoutManager
        val first = manager?.findViewByPosition(0)
        val offset = if (!selecting) {
            when {
                first != null -> recycler.paddingTop - manager.getDecoratedTop(first)
                recycler.canScrollVertically(-1) -> fadeDistance
                else -> 0
            }
        } else 0
        val progress = (offset.toFloat() / fadeDistance).coerceIn(0f, 1f)
        titleView.alpha = 1f - progress
        titleView.translationY = -readerDp(14) * progress
        titleView.visibility = if (progress == 1f) View.INVISIBLE else View.VISIBLE
    }

    // ---------------------------------------------------------------- 交互

    private fun showPreview(index: Int) {
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0x99101620.toInt())
            isClickable = true
            alpha = 0f
            setOnClickListener { dismissPreview() }
        }
        val card = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, readerDp(18).toFloat())
                }
            }
            setBackgroundColor(0x1FFFFFFF)
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image, FrameLayout.LayoutParams(-1, -1))
        // 大图预览不要放大超过原图：541px 的封面拉到 1100px 只会更糊
        val preferred = minOf(readerDp(420), (resources.displayMetrics.widthPixels * 0.6f).toInt())
        val native = nativeCoverWidth(index)
        val width = if (native > 0) minOf(preferred, native) else preferred
        layer.addView(card, FrameLayout.LayoutParams(width, (width * 4 / 3f).toInt(), Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        previewLayer = layer
        bindCoverImage(image, index, width)
        layer.animate().alpha(1f).setDuration(140).start()
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
    }

    /** 原图宽度：优先解压出来的原图，其次用户新增的封面文件，最后才问 drawable。 */
    private fun nativeCoverWidth(index: Int): Int {
        val path = DefaultCoverStore.resolvePath(this, index)
        if (path != null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0) return options.outWidth
        }
        val resourceId = resources.getIdentifier(
            String.format(java.util.Locale.ROOT, "cover_lib_%02d", index + 1),
            "drawable", packageName
        )
        return if (resourceId != 0) getDrawable(resourceId)?.intrinsicWidth ?: 0 else 0
    }

    private fun dismissPreview() {
        val layer = previewLayer ?: return
        previewLayer = null
        layer.animate().alpha(0f).setDuration(120)
            .withEndAction { root.removeView(layer) }
            .start()
    }

    private fun showDeleteDialog() {
        val count = selected.size
        if (count == 0) return
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0x66101620)
            isClickable = true
            setOnClickListener { }
        }
        // 同窗口内的浮层不能用 AGSL 玻璃：它会把包含自己的父树录进 RenderNode，
        // 绘制时无限递归。这里用实心卡片，可靠且够用。
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = readerDp(24).toFloat()
                setColor(0xF7FFFFFF.toInt())
            }
        }
        layer.addView(card, FrameLayout.LayoutParams(readerDp(345), -2, Gravity.CENTER))

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(readerDp(22), readerDp(22), readerDp(22), readerDp(18))
            background = null
        }
        card.addView(column, FrameLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "删除选中的封面？"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(false))
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "将从默认封面列表移除 $count 张封面，用到它们的书会自动换一张。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.soft(false))
            setPadding(readerDp(4), readerDp(10), readerDp(4), readerDp(18))
        }, android.widget.LinearLayout.LayoutParams(-1, -2))

        val actions = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(actions, android.widget.LinearLayout.LayoutParams(-1, readerDp(44)))
        val cancel = actionButton("取消", SkinTheme.title(false), 0x59FFFFFF)
        val confirm = actionButton("确认删除", Color.parseColor("#C0442E"), 0x3DC0442E)
        actions.addView(cancel, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        actions.addView(confirm, android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginStart = readerDp(10)
        })

        fun close() {
            root.removeView(layer)
        }
        cancel.setOnClickListener { close() }
        confirm.setOnClickListener {
            deleteSelected()
            close()
        }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun actionButton(text: String, color: Int, background: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        this.background = GradientDrawable().apply {
            cornerRadius = readerDp(22).toFloat()
            setColor(background)
        }
        isClickable = true
        isFocusable = true
    }

    private fun deleteSelected() {
        val targets = selected.toList()
        if (targets.isEmpty()) return
        val removed = DefaultCoverStore.remove(this, targets)
        if (!removed) {
            toast("至少要保留一张默认封面")
            return
        }
        selecting = false
        selected.clear()
        DefaultCoverStore.invalidate()
        reloadPool()
        // 列表已换新，选择模式也要一起复位，否则「新增封面」入口不会回来
        applySelectionState()
        Thread {
            BookStore.reassignRemovedCovers(this, targets)
            runOnUiThread { toast("已删除 ${targets.size} 张封面") }
        }.start()
    }

    private fun addCover(uri: Uri) {
        if (editingDisabled) {
            toast("封面配置读取失败，暂时不能新增")
            return
        }
        val bitmap = decode(uri) ?: run {
            toast("这张图片打不开，请换一张")
            return
        }
        val cropped = cropToCover(bitmap)
        val index = DefaultCoverStore.addCustom(this, cropped)
        if (cropped !== bitmap) bitmap.recycle()
        if (index == null) {
            toast("封面保存失败，请重试")
            return
        }
        DefaultCoverStore.invalidate()
        reloadPool()
        // 新增的封面排在最前，滚回顶部而不是滚到底
        recycler.scrollToPosition(0)
        toast("已新增封面")
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= 1600) {
            sample *= 2
            longest /= 2
        }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun cropToCover(bitmap: Bitmap): Bitmap {
        val ratio = 3f / 4f
        var width = bitmap.width
        var height = (width / ratio).toInt()
        if (height > bitmap.height) {
            height = bitmap.height
            width = (height * ratio).toInt()
        }
        val x = (bitmap.width - width) / 2
        val y = (bitmap.height - height) / 2
        return runCatching { Bitmap.createBitmap(bitmap, x, y, width, height) }.getOrDefault(bitmap)
    }

    private fun bindCoverImage(image: ImageView, index: Int, size: Int) {
        val resourceId = if (index < DEFAULT_COVER_COUNT) {
            resources.getIdentifier(
                String.format(java.util.Locale.ROOT, "cover_lib_%02d", index + 1),
                "drawable", packageName
            )
        } else 0
        val path = DefaultCoverStore.resolvePath(this, index)
        val key = when {
            resourceId != 0 -> CoverThumbnailLoader.resourceKey(this, resourceId, size, size)
            path != null -> CoverThumbnailLoader.key(path, size, size)
            else -> return
        }
        CoverThumbnailLoader.cached(key)?.bitmap?.let {
            image.setImageBitmap(it)
            return
        }
        val callback: (CoverThumbnailLoader.Result) -> Unit = { result ->
            result.bitmap?.let(image::setImageBitmap)
        }
        if (key.resourceId == 0) CoverThumbnailLoader.load(key, callback)
        else CoverThumbnailLoader.loadResource(resources, key, callback)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 适配器

    /** 封面格子强制正方形：外部给多宽就多高，避免图片把格子撑成竖长条。 */
    private class CellFrame(context: android.content.Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            )
        }
    }

    private inner class CoverAdapter : RecyclerView.Adapter<CoverAdapter.Holder>() {

        private val indexes = mutableListOf<Int>()
        var selecting = false
        var selection: Set<Int> = emptySet()

        fun positionOf(index: Int): Int =
            indexes.indexOf(index) + if (selecting) 0 else 1

        fun submit(list: List<Int>) {
            indexes.clear()
            indexes.addAll(list)
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.coverThumb)
            val check: TextView = view.findViewById(R.id.coverCheck)
            val add: TextView = view.findViewById(R.id.coverAdd)
            var request: CoverThumbnailLoader.Request? = null
            var bound: Int = -1

            fun bindItem(index: Int) {
                if (bound == index) return
                bound = index
                request?.cancel()
                request = null
                image.setImageDrawable(null)
                loadImage()
            }

            fun loadImage() {
                val size = if (cellSize > 0) cellSize else readerDp(150)
                val index = bound
                val resourceId = if (index < DEFAULT_COVER_COUNT) {
                    resources.getIdentifier(
                        String.format(java.util.Locale.ROOT, "cover_lib_%02d", index + 1),
                        "drawable", packageName
                    )
                } else 0
                val path = DefaultCoverStore.resolvePath(this@DefaultCoverActivity, index)
                val key = when {
                    resourceId != 0 -> CoverThumbnailLoader.resourceKey(this@DefaultCoverActivity, resourceId, size, size)
                    path != null -> CoverThumbnailLoader.key(path, size, size)
                    else -> return
                }
                CoverThumbnailLoader.cached(key)?.bitmap?.let {
                    image.setImageBitmap(it)
                    return
                }
                val callback: (CoverThumbnailLoader.Result) -> Unit = { result ->
                    if (bound == index) result.bitmap?.let(image::setImageBitmap)
                }
                request = if (key.resourceId == 0) CoverThumbnailLoader.load(key, callback)
                else CoverThumbnailLoader.loadResource(resources, key, callback)
            }
        }

        override fun getItemCount(): Int = indexes.size + if (selecting) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = CellFrame(this@DefaultCoverActivity).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, readerDp(13).toFloat())
                    }
                }
            }
            val image = ImageView(this@DefaultCoverActivity).apply {
                id = R.id.coverThumb
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x1AFFFFFF)
            }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            val check = TextView(this@DefaultCoverActivity).apply {
                id = R.id.coverCheck
                textSize = 12f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            frame.addView(check, FrameLayout.LayoutParams(readerDp(28), readerDp(28), Gravity.TOP or Gravity.END).apply {
                topMargin = readerDp(8)
                marginEnd = readerDp(8)
            })
            val add = TextView(this@DefaultCoverActivity).apply {
                id = R.id.coverAdd
                text = "＋"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(SkinTheme.soft(SkinStore.load(this@DefaultCoverActivity).dark))
                background = GradientDrawable().apply {
                    cornerRadius = readerDp(13).toFloat()
                    setColor(0x33FFFFFF)
                    setStroke(readerDp(1), 0x66FFFFFF)
                }
                visibility = View.GONE
            }
            frame.addView(add, FrameLayout.LayoutParams(-1, -1))
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val isAddTile = !selecting && position == 0
            if (isAddTile) {
                holder.image.visibility = View.GONE
                holder.check.visibility = View.GONE
                holder.add.visibility = View.VISIBLE
                holder.itemView.setOnClickListener {
                    if (!editingDisabled) photoPicker.launch("image/*") else toast("封面配置读取失败，暂时不能新增")
                }
                holder.itemView.setOnLongClickListener { false }
                return
            }
            val index = indexes[position - if (selecting) 0 else 1]
            holder.add.visibility = View.GONE
            holder.image.visibility = View.VISIBLE
            holder.bindItem(index)
            val isSelected = selecting && index in selection
            if (selecting) {
                val wasHidden = holder.check.getTag(R.id.cover_selection_tag) != true
                holder.check.visibility = View.VISIBLE
                holder.check.text = if (isSelected) "✓" else ""
                holder.check.setTextColor(0xFF25303F.toInt())
                holder.check.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSelected) 0xF2FFFFFF.toInt() else 0x66263347)
                    setStroke(readerDp(1), 0xB3FFFFFF.toInt())
                }
                // 进入删除模式时勾选点缩放淡入，避免整排圆圈「啪」地出现
                if (wasHidden) {
                    holder.check.setTag(R.id.cover_selection_tag, true)
                    holder.check.animate().cancel()
                    holder.check.scaleX = 0.4f
                    holder.check.scaleY = 0.4f
                    holder.check.alpha = 0f
                    holder.check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                }
            } else {
                holder.check.visibility = View.GONE
                holder.check.setTag(R.id.cover_selection_tag, null)
            }
            holder.itemView.setOnClickListener {
                if (selecting) toggleIndex(index) else showPreview(index)
            }
            holder.itemView.setOnLongClickListener {
                if (selecting) toggleIndex(index) else enterSelection(index)
                true
            }
        }

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            holder.request?.cancel()
            holder.request = null
            holder.bound = -1
        }
    }
}

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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat

/**
 * 皮肤页。
 *
 * 三类皮肤（极光 / 纯色 / 照片）都要先看到书架效果再确认；纯色和照片在预览页里直接确认，
 * 预设皮肤走「预览 → 确认」两步。取消离开时会清掉还没应用的自定义背景文件。
 */
class SkinActivity : BaseActivity() {

    override val systemBarDark: Boolean get() = SkinStore.load(this).dark

    private enum class Page { CATALOG, COLOR, PHOTOS, CROP, PREVIEW }
    private enum class Tab { CLASSIC, CUSTOM }

    private lateinit var root: FrameLayout
    /** 背景 + 内容：玻璃按钮的采样源，不含顶栏自身，避免 RenderNode 递归。 */
    private lateinit var contentLayer: FrameLayout
    private lateinit var backdrop: View
    private lateinit var header: View
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: GlassActionButton
    private lateinit var confirmButton: GlassActionButton

    private var applied: Skin = Skin.AURORA
    private var draft: Skin? = null
    private var page = Page.CATALOG
    private var tab = Tab.CLASSIC
    private var lastPage: Page? = null
    private var lastTab: Tab? = null
    private var returnPage = Page.CATALOG
    private var selectedColor = DEFAULT_COLOR
    private var pendingPhotoPath: String? = null
    private var committed = false

    private var cropView: CropView? = null
    private var cropBitmap: Bitmap? = null
    private var previewBooks: List<Book> = emptyList()
    private var previewBooksStamp = -1L

    /** 相册缩略图后台解码：24 张原图在主线程逐张解会明显卡顿。 */
    private val thumbWorker = java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
        Thread(task, "skin-thumbs").apply { isDaemon = true }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applied = SkinStore.load(this)
        if (applied.kind == SkinKind.COLOR) selectedColor = applied.color
        // 预览书单由 render() 按书库文件时间戳实时刷新，不再用一次性快照或示例书名补格。
        root = FrameLayout(this)
        // 与书架页 shelfBackdrop 同构：背景和内容放同一层供玻璃采样；
        // 顶栏单独挂在 root 上，不在这层里，避免玻璃录制时把自己卷进去递归。
        contentLayer = FrameLayout(this)
        backdrop = View(this)
        contentLayer.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this)
        contentLayer.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentLayer, FrameLayout.LayoutParams(-1, -1))
        header = buildHeader()
        root.addView(header, FrameLayout.LayoutParams(-1, readerDp(58)))
        setContentView(root)

        onSystemBarInsets(root) { _, top, _, bottom ->
            val headerHeight = readerDp(58) + top
            (header.layoutParams as FrameLayout.LayoutParams).height = headerHeight
            header.setPadding(0, top, 0, 0)
            content.setPadding(0, headerHeight, 0, bottom)
        }
        ViewCompat.requestApplyInsets(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onDestroy() {
        if (isFinishing && !committed) SkinStore.deletePhoto(pendingPhotoPath)
        cropBitmap?.recycle()
        cropBitmap = null
        thumbWorker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildHeader(): View {
        val header = FrameLayout(this).apply { id = R.id.skinHeader }
        backButton = GlassActionButton(this).apply {
            backdropSource = contentLayer
            // 与书架页一致用默认 control optics；usePopupOptics() 会把折射压到 3/2dp 且关掉高光。
            useBackIcon(SkinTheme.title(false))
            setGlassClickListener {
                if (!goBack()) finish()
            }
        }
        header.addView(backButton, FrameLayout.LayoutParams(readerDp(44), readerDp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = readerDp(12)
        })
        titleView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(false))
        }
        header.addView(titleView, FrameLayout.LayoutParams(-1, -1).apply {
            marginStart = readerDp(60)
            marginEnd = readerDp(60)
        })
        confirmButton = GlassActionButton(this).apply {
            text = "确认"
            textView.textSize = 13f
            backdropSource = contentLayer
            // 与书架页一致用默认 control optics；usePopupOptics() 会把折射压到 3/2dp 且关掉高光。
            setGlassClickListener { confirm() }
        }
        header.addView(confirmButton, FrameLayout.LayoutParams(-2, readerDp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = readerDp(12)
        })
        return header
    }

    /** 书库文件一变就重读，预览用的始终是实时书单（排序口径与真实书架共用）。 */
    private fun refreshPreviewBooks() {
        val stamp = File(filesDir, "library.json").lastModified()
        if (stamp == previewBooksStamp && previewBooksStamp != -1L) return
        previewBooksStamp = stamp
        previewBooks = shelfOrderedBooks(this)
    }

    private fun render() {
        refreshPreviewBooks()
        // 预览页整屏都换成待应用的皮肤：顶栏、状态栏底下、底部留白都要跟着变，
        // 否则用户看到的仍是上一个皮肤，等于只预览了一半。
        val surface = if (page == Page.PREVIEW || page == Page.COLOR) draft ?: applied else applied
        SkinStore.applyTo(this, backdrop, surface)
        window.syncSystemBarIconTone(surface.dark)
        val ink = SkinTheme.title(surface.dark)
        titleView.setTextColor(ink)
        backButton.setBackIconColor(ink)
        confirmButton.textView.setTextColor(ink)
        confirmButton.visibility =
            if (page == Page.COLOR || page == Page.PREVIEW || page == Page.CROP) View.VISIBLE else View.GONE
        confirmButton.text = if (page == Page.CROP) "下一步" else "确认"
        titleView.text = when (page) {
            Page.CATALOG -> "皮肤"
            Page.COLOR -> "自选色"
            Page.PHOTOS -> "全部照片"
            Page.CROP -> "图片预览"
            Page.PREVIEW -> "皮肤预览"
        }
        content.removeAllViews()
        val body = when (page) {
            Page.CATALOG -> buildCatalog()
            Page.COLOR -> buildColorPage()
            Page.PHOTOS -> buildPhotosPage()
            Page.CROP -> buildCropPage()
            Page.PREVIEW -> buildPreviewPage()
        }
        content.addView(body, FrameLayout.LayoutParams(-1, -1))
        // 与书架页一致：内容上拉时标题淡出，换页时重置。
        body.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateHeaderTitle(scrollY) }
        updateHeaderTitle(0)
        GlassStyle.load(this).applyTo(root)
        SkinTheme.applyGlassEdge(root, surface.pale)
        animateContent(body)
    }

    /** 与书架页一致：滚动超过 32dp 后标题完全隐藏。 */
    private fun updateHeaderTitle(scrollY: Int) {
        val fadeDistance = readerDp(32).coerceAtLeast(1)
        val progress = (scrollY.toFloat() / fadeDistance).coerceIn(0f, 1f)
        titleView.alpha = 1f - progress
        titleView.translationY = -readerDp(14) * progress
        titleView.visibility = if (progress == 1f) View.INVISIBLE else View.VISIBLE
    }

    /**
     * 层级切换的过渡：往里走从右侧淡入，往回走从左侧淡入；
     * 经典/自定义这种同级切换用轻微上浮 + 淡入，避免整页横移显得突兀。
     */
    private fun animateContent(body: View) {
        val previousPage = lastPage
        val previousTab = lastTab
        lastPage = page
        lastTab = tab
        val tabSwitch = page == Page.CATALOG && previousPage == Page.CATALOG && previousTab != tab
        if (previousPage != null && previousPage == page && !tabSwitch) return
        val depth = mapOf(Page.CATALOG to 0, Page.COLOR to 1, Page.PHOTOS to 1, Page.CROP to 2, Page.PREVIEW to 3)
        val forward = previousPage == null || (depth[page] ?: 0) >= (depth[previousPage] ?: 0)
        val shift = readerDp(18).toFloat()
        body.alpha = 0f
        body.translationX = when {
            tabSwitch -> 0f
            forward -> shift
            else -> -shift
        }
        body.translationY = if (tabSwitch) readerDp(10).toFloat() else 0f
        body.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .start()
    }

    /** 返回 true 表示已经在页面内部处理了返回。 */
    private fun goBack(): Boolean = when (page) {
        Page.CATALOG -> false
        Page.COLOR, Page.PHOTOS -> {
            page = Page.CATALOG
            render()
            true
        }
        Page.CROP -> {
            page = Page.PHOTOS
            render()
            true
        }
        Page.PREVIEW -> {
            page = returnPage
            if (page == Page.CATALOG) draft = null
            render()
            true
        }
    }

    private fun confirm() {
        when (page) {
            Page.CROP -> {
                val bitmap = cropView?.crop(CropView.targetWidth(readerDp(360), readerDp(500))) ?: run {
                    toast("这张图片暂时无法裁剪，请换一张")
                    return
                }
                val file = SkinStore.savePhoto(this, bitmap)
                if (file == null) {
                    toast("图片保存失败，请重试")
                    return
                }
                SkinStore.deletePhoto(pendingPhotoPath)
                pendingPhotoPath = file.absolutePath
                draft = Skin(
                    kind = SkinKind.PHOTO,
                    photoPath = file.absolutePath,
                    dark = SkinStore.measuredDark(bitmap)
                )
                returnPage = Page.CROP
                page = Page.PREVIEW
                render()
            }
            Page.COLOR, Page.PREVIEW -> applyDraft()
            else -> Unit
        }
    }

    private fun applyDraft() {
        val target = draft ?: return
        SkinStore.save(this, target)
        // 手动选过皮肤就以这次为准：之后切回日间不再被「进夜间前的皮肤」覆盖
        SkinSync.onManualSkinChange(this)
        applied = target
        committed = true
        // 用过的照片留在「全部照片」里给下次复用，只清掉还没应用的临时图
        if (pendingPhotoPath != null && pendingPhotoPath != target.photoPath) {
            SkinStore.deletePhoto(pendingPhotoPath)
            pendingPhotoPath = null
        }
        SkinStore.prunePhotos(this, keepPath = target.photoPath)
        toast("已应用${target.name}")
        finish()
    }

    // ---------------------------------------------------------------- 皮肤列表

    private fun buildCatalog(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(24), 0, readerDp(24), readerDp(36))
        }
        scroll.addView(column, ViewGroup.LayoutParams(-1, -2))

        column.addView(buildSegments(), LinearLayout.LayoutParams(readerDp(228), readerDp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = readerDp(4)
        })

        val available = contentWidth() - readerDp(48)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = readerDp(20) })

        if (tab == Tab.CLASSIC) {
            val gap = readerDp(14)
            val cardWidth = minOf(readerDp(300), (available - gap * 2) / 3)
            Skin.PRESETS.forEachIndexed { index, skin ->
                row.addView(presetCard(skin, cardWidth), LinearLayout.LayoutParams(cardWidth, -2).apply {
                    if (index > 0) marginStart = gap
                })
            }
        } else {
            val gap = readerDp(16)
            val cardWidth = minOf(readerDp(300), (available - gap) / 2)
            row.addView(customColorCard(cardWidth), LinearLayout.LayoutParams(cardWidth, -2))
            row.addView(customPhotoCard(cardWidth), LinearLayout.LayoutParams(cardWidth, -2).apply {
                marginStart = gap
            })
        }

        column.addView(buildHint("选择后先预览，确认后应用"), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = readerDp(18)
        })
        return scroll
    }

    private fun buildSegments(): View {
        val dark = applied.dark
        val track = FrameLayout(this).apply {
            background = rounded(22, if (dark) 0x338094A8 else 0x1E473315)
            setPadding(readerDp(3), readerDp(3), readerDp(3), readerDp(3))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        track.addView(row, FrameLayout.LayoutParams(-1, -1))
        listOf("经典" to Tab.CLASSIC, "自定义" to Tab.CUSTOM).forEach { (label, value) ->
            val selected = tab == value
            val button = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) SkinTheme.title(dark) else SkinTheme.soft(dark))
                background = if (selected) rounded(19, if (dark) 0x59FFFFFF else 0xE9FFFFFF.toInt()) else null
                setOnClickListener {
                    if (tab != value) {
                        tab = value
                        render()
                    }
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return track
    }

    private fun presetCard(skin: Skin, width: Int): View {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = "预览${skin.name}"
            setOnClickListener { openPreview(skin) }
        }
        val card = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = roundedOutline(readerDp(18))
        }
        SkinStore.applyTo(this, card, skin)
        val preview = ShelfPreviewLayout(this).apply {
            usePaintedGlass()
            bind(previewBooks, skin.dark, skin.pale)
        }
        card.addView(preview, FrameLayout.LayoutParams(-1, -1))
        if (sameSkin(skin, applied)) {
            // 「使用中」角标压在缩略图左上角，把预览内容整体下移，避免盖住「书架」标题
            preview.setPadding(0, readerDp(22), 0, 0)
            val badge = TextView(this).apply {
                text = "使用中"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF25303F.toInt())
                gravity = Gravity.CENTER
                setPadding(readerDp(9), readerDp(3), readerDp(9), readerDp(3))
                background = rounded(10, 0xE6FFFFFF.toInt())
            }
            card.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = readerDp(8)
                topMargin = readerDp(8)
            })
        }
        holder.addView(card, LinearLayout.LayoutParams(width, (width * 380f / 320f).toInt()))
        val label = TextView(this).apply {
            text = skin.name
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(applied.dark))
            setPadding(0, readerDp(9), 0, 0)
        }
        holder.addView(label, LinearLayout.LayoutParams(-1, -2))
        return holder
    }

    private fun customCardFace(
        background: android.graphics.drawable.Drawable?,
        icon: Int,
        label: String,
        width: Int,
        iconColor: Int = if (applied.dark) 0xFFEFF3F9.toInt() else 0xFF33404F.toInt()
    ): View {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = label
        }
        val face = FrameLayout(this).apply {
            this.background = background
            clipToOutline = true
            outlineProvider = roundedOutline(readerDp(18))
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(iconColor)
        }
        face.addView(image, FrameLayout.LayoutParams(readerDp(42), readerDp(42), Gravity.CENTER))
        holder.addView(face, LinearLayout.LayoutParams(width, (width * 380f / 320f).toInt()))
        holder.addView(TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(applied.dark))
            setPadding(0, readerDp(9), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return holder
    }

    private fun customColorCard(width: Int): View {
        val spectrum = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFC7D9.toInt(), 0xFFFDF0A8.toInt(), 0xFFB8EBD0.toInt(), 0xFFAEDCFF.toInt(), 0xFFCDBBFF.toInt())
        )
        return customCardFace(spectrum, R.drawable.ic_skin_palette, "自选色", width, Color.WHITE).apply {
            setOnClickListener {
                val color = when (applied.kind) {
                    SkinKind.COLOR -> applied.color
                    else -> selectedColor
                }
                selectedColor = color
                draft = Skin(SkinKind.COLOR, color, dark = Skin.isDarkColor(color))
                page = Page.COLOR
                render()
            }
        }
    }

    private fun customPhotoCard(width: Int): View {
        val dark = applied.dark
        val glass = rounded(18, if (dark) 0x33FFFFFF else 0x40FFFFFF).apply {
            setStroke(readerDp(1), if (dark) 0x33FFFFFF else 0x8CFFFFFF.toInt())
        }
        return customCardFace(glass, R.drawable.ic_skin_image, "背景自定义", width).apply {
            setOnClickListener {
                page = Page.PHOTOS
                render()
            }
        }
    }

    private fun buildHint(text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.ic_skin_image)
            setColorFilter(SkinTheme.soft(applied.dark))
        }
        row.addView(image, LinearLayout.LayoutParams(readerDp(14), readerDp(14)))
        row.addView(TextView(this).apply {
            setText(text)
            textSize = 11f
            setTextColor(SkinTheme.soft(applied.dark))
            setPadding(readerDp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun openPreview(skin: Skin) {
        draft = skin
        returnPage = Page.CATALOG
        page = Page.PREVIEW
        render()
    }

    // ---------------------------------------------------------------- 自选色

    private fun buildColorPage(): View {
        val current = draft ?: Skin(SkinKind.COLOR, selectedColor, dark = Skin.isDarkColor(selectedColor))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val previewHost = FrameLayout(this)
        val preview = ShelfPreviewPage(this).apply {
            bind(previewBooks, current.dark)
            applySolidColor(current.color)
        }
        previewHost.addView(preview, FrameLayout.LayoutParams(-1, -1))
        column.addView(previewHost, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(24), readerDp(14), readerDp(24), readerDp(16))
        }
        column.addView(panel, LinearLayout.LayoutParams(-1, -2))

        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 2
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, -2))

        val swatches = mutableListOf<Pair<Int, TextView>>()
        val columns = 7
        val cellWidth = (contentWidth() - readerDp(48)) / columns
        val cellHeight = minOf(readerDp(52), cellWidth)
        Skin.SWATCHES.forEach { color ->
            val swatch = TextView(this).apply {
                background = swatchDrawable(color, color == current.color, applied.dark)
                isClickable = true
                isFocusable = true
                contentDescription = String.format("#%06X", 0xFFFFFF and color)
                // 圆点自己吃掉触摸，必须在这里也接上点击，否则点色板没反应
                setOnClickListener { pickColor(color) }
            }
            val cell = FrameLayout(this).apply {
                addView(swatch, FrameLayout.LayoutParams(cellHeight - readerDp(12), cellHeight - readerDp(12), Gravity.CENTER))
            }
            cell.setOnClickListener { pickColor(color) }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellWidth
                height = cellHeight
            })
            swatches.add(color to swatch)
        }

        val hsv = FloatArray(3).also { Color.colorToHSV(current.color, it) }
        val moreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(moreRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = readerDp(6) })
        moreRow.addView(TextView(this).apply {
            text = "更多颜色"
            textSize = 12f
            setTextColor(SkinTheme.soft(applied.dark))
        }, LinearLayout.LayoutParams(readerDp(70), -2))
        val hexLabel = TextView(this).apply {
            text = String.format("#%06X", 0xFFFFFF and current.color)
            textSize = 11f
            setTextColor(SkinTheme.soft(applied.dark))
            gravity = Gravity.END
        }
        moreRow.addView(hexLabel, LinearLayout.LayoutParams(0, -2, 1f))

        fun update(color: Int) {
            selectedColor = color
            val dark = Skin.isDarkColor(color)
            draft = Skin(SkinKind.COLOR, color, dark = dark)
            preview.applySolidColor(color)
            hexLabel.text = String.format("#%06X", 0xFFFFFF and color)
            swatches.forEach { (value, view) ->
                view.background = swatchDrawable(value, value == color, applied.dark)
            }
            hsv.also { Color.colorToHSV(color, it) }
        }

        val hueBar = tintedSeekBar()
        hueBar.max = 359
        hueBar.progress = hsv[0].toInt()
        val toneBar = tintedSeekBar()
        toneBar.max = 100
        toneBar.progress = ((hsv[2] - 0.22f) / 0.73f * 100f).toInt().coerceIn(0, 100)
        listOf("色相" to hueBar, "明度" to toneBar).forEach { (label, bar) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(SkinTheme.soft(applied.dark))
            }, LinearLayout.LayoutParams(readerDp(44), -2))
            row.addView(bar, LinearLayout.LayoutParams(0, readerDp(30), 1f))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = readerDp(2) })
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val hue = if (hueBar.progress == 0) 0f else hueBar.progress.toFloat()
                    val value = 0.22f + toneBar.progress / 100f * 0.73f
                    update(Color.HSVToColor(floatArrayOf(hue, 0.32f, value)))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        return column
    }

    private fun pickColor(color: Int) {
        selectedColor = color
        draft = Skin(SkinKind.COLOR, color, dark = Skin.isDarkColor(color))
        render()
    }

    private fun swatchDrawable(color: Int, selected: Boolean, dark: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                if (selected) readerDp(2) else readerDp(1),
                if (selected) SkinTheme.title(dark) else 0x33FFFFFF
            )
        }

    private fun tintedSeekBar(): SeekBar = SeekBar(this).apply {
        progressTintList = android.content.res.ColorStateList.valueOf(0x991F2A38.toInt())
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x331F2A38)
        thumbTintList = android.content.res.ColorStateList.valueOf(0xFF1F2A38.toInt())
        splitTrack = false
    }

    // ---------------------------------------------------------------- 照片与裁剪

    private fun buildPhotosPage(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(24), 0, readerDp(24), readerDp(28))
        }
        scroll.addView(column, ViewGroup.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "照片只保存在本机，不会上传 · 长按可删除"
            textSize = 11f
            setTextColor(SkinTheme.soft(applied.dark))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = readerDp(6); bottomMargin = readerDp(14) })

        val columns = (contentWidth() / readerDp(150)).coerceIn(3, 6)
        val cell = (contentWidth() - readerDp(48) - readerDp(12) * (columns - 1)) / columns
        val grid = GridLayout(this).apply { columnCount = columns }
        column.addView(grid, LinearLayout.LayoutParams(-1, -2))

        fun addTile(view: View) {
            grid.addView(view, GridLayout.LayoutParams().apply {
                width = cell
                height = cell
                setMargins(0, 0, readerDp(12), readerDp(12))
            })
        }

        addTile(pickTile(cell))
        SkinStore.savedPhotos(this).forEach { file ->
            addTile(photoTile(file.absolutePath, cell))
        }
        return scroll
    }

    private fun pickTile(size: Int): View {
        val dark = applied.dark
        val frame = FrameLayout(this).apply {
            background = rounded(14, if (dark) 0x2EFFFFFF else 0x33FFFFFF).apply {
                setStroke(readerDp(1), if (dark) 0x40FFFFFF else 0x8CFFFFFF.toInt())
            }
            isClickable = true
            isFocusable = true
            contentDescription = "从设备选择照片"
            setOnClickListener { photoPicker.launch("image/*") }
        }
        val label = TextView(this).apply {
            text = "＋"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(dark))
        }
        frame.addView(label, FrameLayout.LayoutParams(size / 3, size / 3, Gravity.CENTER))
        frame.addView(TextView(this).apply {
            text = "从设备选择"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.soft(dark))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = readerDp(10)
        })
        return frame
    }

    private fun photoTile(path: String, size: Int): View {
        val frame = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = roundedOutline(readerDp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                pendingPhotoPath = path
                draft = Skin(SkinKind.PHOTO, photoPath = path, dark = SkinStore.decode(path, 120)?.let(SkinStore::measuredDark) ?: false)
                returnPage = Page.PHOTOS
                page = Page.PREVIEW
                render()
            }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val target = image
        thumbWorker.execute {
            val bitmap = SkinStore.decode(path, size) ?: return@execute
            runOnUiThread {
                if (!isFinishing && !isDestroyed && target.parent != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.setOnLongClickListener {
            if (path == applied.photoPath) toast("这张照片正在用，先换一张再删") else confirmDeletePhoto(path)
            true
        }
        return frame
    }

    /** 长按照片走这里：只从「全部照片」移除，不动书库。 */
    private fun confirmDeletePhoto(path: String) {
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0x66101620)
            isClickable = true
        }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = readerDp(24).toFloat()
                setColor(0xF7FFFFFF.toInt())
            }
        }
        layer.addView(card, FrameLayout.LayoutParams(readerDp(345), -2, Gravity.CENTER))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(readerDp(22), readerDp(22), readerDp(22), readerDp(18))
        }
        card.addView(column, FrameLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "删除这张照片？"
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF222222.toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "只会从「全部照片」里移除，不影响书库。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A857D.toInt())
            setPadding(readerDp(4), readerDp(10), readerDp(4), readerDp(18))
        }, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(row, LinearLayout.LayoutParams(-1, readerDp(44)))
        val cancel = paintedButton("取消", 0xFF222222.toInt(), 0x59FFFFFF)
        val remove = paintedButton("删除", 0xFFC0442E.toInt(), 0x3DC0442E)
        row.addView(cancel, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(remove, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = readerDp(10) })

        fun close() {
            root.removeView(layer)
        }
        cancel.setOnClickListener { close() }
        remove.setOnClickListener {
            SkinStore.deletePhoto(path)
            close()
            render()
            toast("已删除照片")
        }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun paintedButton(label: String, color: Int, background: Int): TextView = TextView(this).apply {
        text = label
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

    private fun buildCropPage(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(24), readerDp(6), readerDp(24), readerDp(18))
        }
        val wrapper = FrameLayout(this)
        val clip = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = roundedOutline(readerDp(18))
        }
        val view = CropView(this).apply {
            cropBitmap?.let { setImage(it) }
            onZoomChanged = { }
        }
        cropView = view
        clip.addView(view, FrameLayout.LayoutParams(-1, -1))
        wrapper.addView(clip, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        wrapper.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            if (right != oldRight || bottom != oldBottom) {
                val height = bottom - readerDp(4)
                val width = minOf(right, (height * 0.72f).toInt())
                if (width > 0 && height > 0 && (clip.width != width || clip.height != height)) {
                    clip.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
                }
            }
        }
        column.addView(wrapper, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, readerDp(12), 0, 0)
        }
        val zoomLabel = TextView(this).apply {
            text = "1.0×"
            textSize = 12f
            setTextColor(SkinTheme.soft(applied.dark))
            minWidth = readerDp(46)
            gravity = Gravity.CENTER
        }
        val bar = tintedSeekBar().apply {
            max = 200
            progress = 0
        }
        val minus = TextView(this).apply {
            text = "−"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(applied.dark))
            setOnClickListener {
                bar.progress = (bar.progress - 15).coerceAtLeast(0)
            }
        }
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.title(applied.dark))
            setOnClickListener {
                bar.progress = (bar.progress + 15).coerceAtMost(200)
            }
        }
        controls.addView(minus, LinearLayout.LayoutParams(readerDp(32), readerDp(32)))
        controls.addView(bar, LinearLayout.LayoutParams(0, readerDp(30), 1f))
        controls.addView(plus, LinearLayout.LayoutParams(readerDp(32), readerDp(32)))
        controls.addView(zoomLabel, LinearLayout.LayoutParams(-2, -2))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1f + progress / 100f
                cropView?.setZoom(zoom)
                zoomLabel.text = String.format("%.1f×", zoom)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))

        column.addView(TextView(this).apply {
            text = "拖动调整位置 · 双指缩放 · 下一步查看书架效果"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.soft(applied.dark))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = readerDp(6) })
        return column
    }

    private fun buildPreviewPage(): View {
        val skin = draft ?: applied
        val host = FrameLayout(this)
        val page = ShelfPreviewPage(this).apply {
            bind(previewBooks, skin.dark)
            applySkin(skin)
        }
        host.addView(page, FrameLayout.LayoutParams(-1, -1))
        host.addView(TextView(this).apply {
            text = "${skin.name} · 确认后应用"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(SkinTheme.soft(skin.dark))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = readerDp(24)
        })
        return host
    }

    // ---------------------------------------------------------------- 工具

    private fun loadPhoto(uri: Uri) {
        val bitmap = decodePicked(uri)
        if (bitmap == null) {
            toast("这张图片打不开，请换一张")
            return
        }
        cropBitmap?.recycle()
        cropBitmap = bitmap
        page = Page.CROP
        render()
    }

    private fun decodePicked(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= 2200) {
            sample *= 2
            longest /= 2
        }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun contentWidth(): Int {
        val width = content.width
        return if (width > 0) width else resources.displayMetrics.widthPixels
    }

    private fun rawDp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(radius: Int, color: Int) = GradientDrawable().apply {
        cornerRadius = readerDp(radius).toFloat()
        setColor(color)
    }

    private fun roundedOutline(radius: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
        }
    }

    private fun sameSkin(left: Skin, right: Skin): Boolean = when (left.kind) {
        SkinKind.AURORA -> right.kind == SkinKind.AURORA
        SkinKind.COLOR -> right.kind == SkinKind.COLOR && left.color == right.color
        SkinKind.PHOTO -> right.kind == SkinKind.PHOTO && left.photoPath == right.photoPath
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFF90CBB1.toInt()
    }
}

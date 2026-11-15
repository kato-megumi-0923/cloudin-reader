package com.cloudin.reader

import com.cloudin.refract.GlassView

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

internal enum class SkinKind { AURORA, COLOR, PHOTO }

/**
 * 书架皮肤。只有两种真实形态：整块纯色，或一张本地照片；极光沿用内置图层。
 * [dark] 决定正文与图标的明暗，不参与皮肤本身的渲染。
 */
internal data class Skin(
    val kind: SkinKind = SkinKind.AURORA,
    val color: Int = 0xFFFFFFFF.toInt(),
    val photoPath: String? = null,
    val dark: Boolean = false
) {
    val name: String
        get() = when (kind) {
            SkinKind.AURORA -> "极光"
            SkinKind.PHOTO -> "背景自定义"
            SkinKind.COLOR -> when (color) {
                WHITE -> "纯净白"
                INK_BLACK, LEGACY_INK_BLACK -> "炫酷黑"
                NIGHT_DARK -> "夜间"
                else -> "自选色"
            }
        }

    /** 近白背景（纯净白 / 很浅的自选色）：白色玻璃按钮会融进背景，需要描一圈边。 */
    val pale: Boolean get() = kind == SkinKind.COLOR && luminance(color) > 0.86

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        /** 炫酷黑：改成和阅读背景「雅黑」一致的纯黑（原来 #1A1D24 和「夜间」太接近）。 */
        const val INK_BLACK = 0xFF000000.toInt()

        /** 旧版炫酷黑的取色，只用于让老存档还能显示成「炫酷黑」而不是「自选色」。 */
        private const val LEGACY_INK_BLACK = 0xFF1A1D24.toInt()

        val AURORA = Skin(SkinKind.AURORA)
        val PURE_WHITE = Skin(SkinKind.COLOR, WHITE)
        val COOL_BLACK = Skin(SkinKind.COLOR, INK_BLACK, dark = true)

        /** 夜间模式专用的书架深色：比炫酷黑更暗、更偏蓝，和阅读层「夜间」同一色系。 */
        const val NIGHT_DARK = 0xFF141A29.toInt()
        val NIGHT = Skin(SkinKind.COLOR, NIGHT_DARK, dark = true)

        /** 「经典」分类里的三个预设，顺序即展示顺序。 */
        val PRESETS = listOf(AURORA, PURE_WHITE, NIGHT, COOL_BLACK)

        /** 相对亮度低于该值就按深色皮肤处理，与设计稿的 0.26 阈值一致。 */
        fun isDarkColor(color: Int): Boolean = luminance(color) < 0.26

        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val v = value / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }

        /** 设计稿里的自选色板。 */
        val SWATCHES = intArrayOf(
            0xFF353B45.toInt(), 0xFF9A69CF.toInt(), 0xFF7463C8.toInt(), 0xFF5B82D1.toInt(),
            0xFF65B5E2.toInt(), 0xFF77D1D9.toInt(), 0xFF90CBB1.toInt(), 0xFFBDD395.toInt(),
            0xFFF0D387.toInt(), 0xFFE6AE88.toInt(), 0xFFD7A3BB.toInt(), 0xFFC8B8DE.toInt(),
            0xFFE4DFD3.toInt(), 0xFFF8FAFC.toInt()
        )
    }
}

/** 皮肤读写。存在 appearance 偏好里，与玻璃样式同一份。 */
internal object SkinStore {

    private const val PREFS = "appearance"
    private const val KEY_KIND = "skin_kind"
    private const val KEY_COLOR = "skin_color"
    private const val KEY_PHOTO = "skin_photo"
    private const val KEY_DARK = "skin_dark"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Skin {
        val p = prefs(context)
        val kind = p.getString(KEY_KIND, null)
            ?.let { name -> SkinKind.entries.firstOrNull { it.name == name } }
            ?: return Skin.AURORA
        return when (kind) {
            SkinKind.AURORA -> Skin.AURORA
            SkinKind.COLOR -> {
                val color = p.getInt(KEY_COLOR, Skin.WHITE)
                Skin(SkinKind.COLOR, color, dark = p.getBoolean(KEY_DARK, Skin.isDarkColor(color)))
            }
            SkinKind.PHOTO -> {
                val path = p.getString(KEY_PHOTO, null)
                if (path == null || !File(path).isFile) Skin.AURORA
                else Skin(SkinKind.PHOTO, photoPath = path, dark = p.getBoolean(KEY_DARK, false))
            }
        }
    }

    fun save(context: Context, skin: Skin) {
        prefs(context).edit()
            .putString(KEY_KIND, skin.kind.name)
            .putInt(KEY_COLOR, skin.color)
            .putString(KEY_PHOTO, skin.photoPath)
            .putBoolean(KEY_DARK, skin.dark)
            .apply()
        // 通知正在显示的设置类页面重建：否则背景/标题是旧配色、卡片是新配色
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    /** 皮肤变化广播，只在应用内传递。 */
    const val ACTION_CHANGED = "com.cloudin.reader.SKIN_CHANGED"

    fun skinsDir(context: Context): File = File(context.filesDir, "skins").apply { mkdirs() }

    /** 保存裁剪结果；文件名用时间戳，避免覆盖用户上一张背景。 */
    fun savePhoto(context: Context, bitmap: Bitmap): File? = runCatching {
        val file = File(skinsDir(context), "skin-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        file
    }.getOrNull()

    fun deletePhoto(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    /** 历史上用过的自定义背景，最新的在前；「全部照片」直接复用这些。 */
    fun savedPhotos(context: Context): List<File> =
        skinsDir(context).listFiles { f -> f.isFile && f.name.startsWith("skin-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 保留最近 [MAX_PHOTOS] 张（正在使用的那张永远保留），避免无上限堆积。 */
    fun prunePhotos(context: Context, keepPath: String?) {
        val photos = savedPhotos(context)
        if (photos.size <= MAX_PHOTOS) return
        photos.drop(MAX_PHOTOS).forEach { file ->
            if (file.absolutePath != keepPath) runCatching { file.delete() }
        }
    }

    private const val MAX_PHOTOS = 24

    /** 照片皮肤用的解码：最大边 1600、inSampleSize 最多 8，避免超大图吃掉内存。 */
    fun decode(path: String, maxDimension: Int = 1600): Bitmap? =
        decodeSampledFile(path, maxDimension, limit = 8)

    private var cachedPath: String? = null
    private var cachedBitmap: Bitmap? = null

    /** 换皮肤/退出照片皮肤时释放解码缓存（1600px 的位图约 10 MB）。 */
    fun releaseCache() {
        cachedPath = null
        cachedBitmap = null
    }

    /**
     * 照片皮肤每次都要给一个新的 BitmapDrawable：
     * Drawable 的 bounds 由使用它的 View 设置，同一个实例挂到两个 View 上会互相覆盖，
     * 玻璃采样（target.draw）时就会抓到错位或空白的一块，表现为「底栏不透明」。
     * 位图本身仍然缓存，解码只做一次。
     */
    fun background(context: Context, skin: Skin): Drawable? {
        return when (skin.kind) {
            // 换成纯色/极光后不再需要那张解码好的大图，丢掉静态引用让 GC 能回收；
            // 已挂到 View 上的 BitmapDrawable 仍持有自己的引用，这里不 recycle。
            SkinKind.AURORA -> {
                releaseCache()
                null
            }
            SkinKind.COLOR -> {
                releaseCache()
                android.graphics.drawable.ColorDrawable(skin.color)
            }
            SkinKind.PHOTO -> {
                val path = skin.photoPath ?: return null
                if (cachedPath != path || cachedBitmap == null) {
                    cachedBitmap = decode(path) ?: return null
                    cachedPath = path
                }
                BitmapDrawable(context.resources, cachedBitmap).apply {
                    gravity = android.view.Gravity.FILL
                    isFilterBitmap = true
                }
            }
        }
    }

    /** 把皮肤铺到某个充当整页背景的 View 上。 */
    fun applyTo(context: Context, view: View, skin: Skin) {
        val drawable = background(context, skin)
        if (drawable != null) {
            view.background = drawable
        } else {
            view.setBackgroundResource(R.drawable.bg_aurora)
        }
    }

    /** 照片整体偏暗时用浅色文字。 */
    fun measuredDark(bitmap: Bitmap): Boolean = averageLuminance(bitmap, 24) < 112.0
}

/**
 * 阅读夜间模式与书架皮肤联动。
 *
 * 规则：进夜间一律用炫酷黑，并记住进夜间之前的皮肤；切回日间时还原那份皮肤。
 * 如果进夜间前本来就是炫酷黑，则不记快照、切回日间也不动它。
 * 用户在皮肤页手动选过就以手动选择为准，日间不再被还原覆盖。
 */
internal object SkinSync {

    private const val PREFS = "appearance"
    private const val KEY_BEFORE_NIGHT = "skin_before_night"

    /** 快照是否来自「阅读页手动点夜间」。这种要延续到书架，不能被 NightMode 自动还原。 */
    private const val KEY_BEFORE_NIGHT_MANUAL = "skin_before_night_manual"

    fun onReaderTheme(context: Context, dark: Boolean, manual: Boolean = false) {
        if (dark) enterNight(context, manual) else leaveNight(context)
    }

    /** 快照是不是用户手动切夜间留下的。 */
    fun snapshotIsManual(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BEFORE_NIGHT_MANUAL, false)

    /** 手动换皮肤：清掉快照，避免之后切日间又被拽回旧皮肤。 */
    fun onManualSkinChange(context: Context) {
        prefs(context).edit().remove(KEY_BEFORE_NIGHT).apply()
    }

    private fun enterNight(context: Context, manual: Boolean) {
        val current = SkinStore.load(context)
        if (current.isNightDark) return
        val p = prefs(context)
        if (p.getString(KEY_BEFORE_NIGHT, null) == null) {
            // 记一次来源：手动切的要能延续到书架，自动切的（夜间模式）由 NightMode 自己收回
            p.edit()
                .putString(KEY_BEFORE_NIGHT, encode(current))
                .putBoolean(KEY_BEFORE_NIGHT_MANUAL, manual)
                .apply()
        }
        SkinStore.save(context, Skin.NIGHT)
    }

    /**
     * 退出夜间时把皮肤还原成进夜间前那张。没有快照（本来是手动选的炫酷黑）就什么都不做。
     * 返回是否真的换了皮肤，调用方据此决定要不要刷新界面。
     */
    fun restoreNightSnapshot(context: Context): Boolean {
        val p = prefs(context)
        val stored = p.getString(KEY_BEFORE_NIGHT, null) ?: return false
        val skin = decode(stored) ?: run {
            p.edit().remove(KEY_BEFORE_NIGHT).remove(KEY_BEFORE_NIGHT_MANUAL).apply()
            return false
        }
        p.edit().remove(KEY_BEFORE_NIGHT).remove(KEY_BEFORE_NIGHT_MANUAL).apply()
        SkinStore.save(context, skin)
        return true
    }

    private fun leaveNight(context: Context) {
        restoreNightSnapshot(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(skin: Skin): String = org.json.JSONObject().apply {
        put("kind", skin.kind.name)
        put("color", skin.color)
        put("photo", skin.photoPath)
        put("dark", skin.dark)
    }.toString()

    private fun decode(raw: String): Skin? = runCatching {
        val o = org.json.JSONObject(raw)
        val kind = SkinKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
        if (kind == SkinKind.PHOTO) return null // 照片可能已被清理，直接用当前皮肤更稳妥
        Skin(
            kind = kind,
            color = o.optInt("color", Skin.WHITE),
            photoPath = null,
            dark = o.optBoolean("dark", false)
        )
    }.getOrNull()
}

/**
 * 是否是「夜间模式」专用的那张皮肤。
 * 只认 NIGHT_DARK：手动选的炫酷黑要靠 enterNight 换成夜间蓝（换之前会存快照，退出夜间再还原），
 * 否则夜间模式开着也还是原来那张偏中性的黑。
 */
internal val Skin.isNightDark: Boolean
    get() = kind == SkinKind.COLOR && color == Skin.NIGHT_DARK

@Deprecated("用 isNightDark", ReplaceWith("isNightDark"))
internal val Skin.isCoolBlack: Boolean get() = isNightDark

/**
 * 深色皮肤下的文字配色。只改写「本来就是书架浅色」的那几个色值，
 * 原值记在 tag 里，切回浅色皮肤时按原值还原。
 */
internal object SkinTheme {

    private val TAG = R.id.skin_text_color_tag
    private val TAG_ICON = R.id.skin_icon_tint_tag
    private val TAG_GLASS = R.id.skin_glass_tint_tag
    private val TAG_SKIP = R.id.skin_skip_tag

    private val light = intArrayOf(
        0xFF222222.toInt(), // shelf_title
        0xFF9A9A9A.toInt(), // shelf_meta
        0xFF8A857D.toInt(), // ink_soft
        0xFF2B2A28.toInt(), // ink
        0xFF505462.toInt(), // shelf_secondary_ink
        0xFFC0392B.toInt()  // shelf_tab_selected
    )

    private val darkColors = intArrayOf(
        0xFFF2F4F8.toInt(),
        0xFFAEB6C4.toInt(),
        0xFFA8B1BF.toInt(),
        0xFFEDF0F5.toInt(),
        0xFFC6CDD8.toInt(),
        0xFFE8887A.toInt()
    )

    fun title(dark: Boolean): Int = if (dark) darkColors[0] else light[0]

    fun meta(dark: Boolean): Int = if (dark) darkColors[1] else light[1]

    fun soft(dark: Boolean): Int = if (dark) darkColors[2] else light[2]

    fun accent(dark: Boolean): Int = if (dark) darkColors[5] else light[5]

    /** 近白皮肤下给玻璃控件描一圈淡边，否则白色按钮在白色背景上完全看不出边界。 */
    fun applyGlassEdge(root: View, pale: Boolean) {
        if (root is ShelfTabBar) {
            root.setSkinPale(pale)
            return
        }
        if (root is GlassView) {
            val radius = root.cornerRadius
            if (pale) {
                // 第一次染色前把原值记下来：不还原的话，从近白皮肤切走之后
                // 玻璃会一直带着冷灰染色和偏高的不透明度。
                if (root.getTag(TAG_GLASS) == null) {
                    root.setTag(
                        TAG_GLASS,
                        intArrayOf(root.tintColor, (root.tintAlpha * 1000).toInt())
                    )
                }
                // 白色背景上纯白玻璃几乎没有体积：给一点冷灰染色 + 一圈淡边
                root.tintColor = 0xFFD3DAE3.toInt()
                root.tintAlpha = maxOf(root.tintAlpha, 0.34f)
                root.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke(root.readerDp(1), 0x3D1A2430)
                    setColor(android.graphics.Color.TRANSPARENT)
                }
            } else {
                (root.getTag(TAG_GLASS) as? IntArray)?.let { original ->
                    root.tintColor = original[0]
                    root.tintAlpha = original[1] / 1000f
                    root.setTag(TAG_GLASS, null)
                }
                root.foreground = null
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) applyGlassEdge(root.getChildAt(index), pale)
        }
    }

    /**
     * 找出颜色属于哪个语义槽位（0..5）。
     *
     * 两个表都查：这样即便某个 View 是在深色皮肤下创建的、当前值是深色版，
     * 也能推回浅色槽位，切浅色时不会卡在近白色（白底上等于消失）。
     */
    private fun slotOf(color: Int): Int {
        val lightSlot = light.indexOf(color)
        if (lightSlot >= 0) return lightSlot
        return darkColors.indexOf(color)
    }

    /**
     * 标记这个 View 不参与皮肤换色。
     *
     * 换色是按「颜色值落在哪个语义槽位」判断的，不是按 View 身份：
     * 只要 tint/textColor 等于表里的 6 个语义色之一，就会被一起翻转。
     * 想让某个图标保持固定颜色（品牌色、图片本身带色等），调这个再自己设色。
     */
    fun ignoreSkin(view: View) {
        view.setTag(TAG_SKIP, true)
    }

    fun isSkinIgnored(view: View): Boolean = view.getTag(TAG_SKIP) == true

    fun applyTo(root: View, dark: Boolean) {
        when (root) {
            is TextView -> recolor(root, dark)
            is android.widget.ImageView -> recolorIcon(root, dark)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) applyTo(root.getChildAt(index), dark)
        }
    }

    /**
     * XML 里用 tint 上色的图标同样要跟着皮肤翻转，否则深色底上会糊成一片。
     *
     * 原色必须记在 tag 里：不记的话切回浅色皮肤时当前 tint 已经是深色值，
     * 在 light 表里找不到、直接跳过，图标会停在近白色——白底上等于消失。
     */
    private fun recolorIcon(view: android.widget.ImageView, dark: Boolean) {
        if (isSkinIgnored(view)) return
        val stored = view.getTag(TAG_ICON) as? Int
        val base = stored ?: view.imageTintList?.defaultColor ?: return
        val slot = slotOf(base)
        if (slot < 0) return
        // tag 里统一存浅色基准值，切来切去都以它为准，不会一轮之后就找不到槽位
        if (stored == null) view.setTag(TAG_ICON, light[slot])
        view.imageTintList = android.content.res.ColorStateList.valueOf(
            if (dark) darkColors[slot] else light[slot]
        )
    }

    fun recolor(view: TextView, dark: Boolean) {
        if (isSkinIgnored(view)) return
        val stored = view.getTag(TAG) as? Int
        val base = stored ?: view.currentTextColor
        val slot = slotOf(base)
        if (slot < 0) return
        if (stored == null) view.setTag(TAG, light[slot])
        view.setTextColor(if (dark) darkColors[slot] else light[slot])
    }
}

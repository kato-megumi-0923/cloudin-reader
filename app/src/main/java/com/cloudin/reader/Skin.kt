package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
                INK_BLACK -> "炫酷黑"
                else -> "自选色"
            }
        }

    /** 近白背景（纯净白 / 很浅的自选色）：白色玻璃按钮会融进背景，需要描一圈边。 */
    val pale: Boolean get() = kind == SkinKind.COLOR && luminance(color) > 0.86

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val INK_BLACK = 0xFF1A1D24.toInt()

        val AURORA = Skin(SkinKind.AURORA)
        val PURE_WHITE = Skin(SkinKind.COLOR, WHITE)
        val COOL_BLACK = Skin(SkinKind.COLOR, INK_BLACK, dark = true)

        /** 「经典」分类里的三个预设，顺序即展示顺序。 */
        val PRESETS = listOf(AURORA, PURE_WHITE, COOL_BLACK)

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
    }

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

    fun decode(path: String, maxDimension: Int = 1600): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxDimension && sample < 8) {
            sample *= 2
            longest /= 2
        }
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }.getOrNull()

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

    fun measuredDark(bitmap: Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 24)
        val stepY = maxOf(1, bitmap.height / 24)
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }
        return count > 0 && total / count < 112.0
    }
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

    fun onReaderTheme(context: Context, dark: Boolean) {
        if (dark) enterNight(context) else leaveNight(context)
    }

    /** 手动换皮肤：清掉快照，避免之后切日间又被拽回旧皮肤。 */
    fun onManualSkinChange(context: Context) {
        prefs(context).edit().remove(KEY_BEFORE_NIGHT).apply()
    }

    private fun enterNight(context: Context) {
        val current = SkinStore.load(context)
        if (current.isCoolBlack) return
        val p = prefs(context)
        if (p.getString(KEY_BEFORE_NIGHT, null) == null) {
            p.edit().putString(KEY_BEFORE_NIGHT, encode(current)).apply()
        }
        SkinStore.save(context, Skin.COOL_BLACK)
    }

    private fun leaveNight(context: Context) {
        val p = prefs(context)
        val stored = p.getString(KEY_BEFORE_NIGHT, null) ?: return
        p.edit().remove(KEY_BEFORE_NIGHT).apply()
        decode(stored)?.let { SkinStore.save(context, it) }
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

/** 皮肤处理后的身份判断：炫酷黑即「夜间默认皮肤」。 */
internal val Skin.isCoolBlack: Boolean
    get() = kind == SkinKind.COLOR && color == Skin.INK_BLACK

/**
 * 深色皮肤下的文字配色。只改写「本来就是书架浅色」的那几个色值，
 * 原值记在 tag 里，切回浅色皮肤时按原值还原。
 */
internal object SkinTheme {

    private val TAG = R.id.skin_text_color_tag
    private val TAG_ICON = R.id.skin_icon_tint_tag
    private val TAG_GLASS = R.id.skin_glass_tint_tag

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
        if (root is AgslGlassView) {
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
                    setStroke(dp(root, 1), 0x3D1A2430)
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

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

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
        val stored = view.getTag(TAG_ICON) as? Int
        val base = stored ?: view.imageTintList?.defaultColor ?: return
        val slot = light.indexOf(base)
        if (slot < 0) return
        if (stored == null) view.setTag(TAG_ICON, base)
        view.imageTintList = android.content.res.ColorStateList.valueOf(
            if (dark) darkColors[slot] else light[slot]
        )
    }

    fun recolor(view: TextView, dark: Boolean) {
        val stored = view.getTag(TAG) as? Int
        val base = stored ?: view.currentTextColor
        val slot = light.indexOf(base)
        if (slot < 0) return
        if (stored == null) view.setTag(TAG, base)
        view.setTextColor(if (dark) darkColors[slot] else light[slot])
    }
}

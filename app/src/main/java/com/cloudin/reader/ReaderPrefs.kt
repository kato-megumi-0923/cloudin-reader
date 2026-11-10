package com.cloudin.reader

import android.content.Context
import android.graphics.Color

object ReaderPrefs {
    const val THEME_PAPER = 0
    const val THEME_WHITE = 1
    const val THEME_NIGHT = 2
    const val THEME_GREEN = 3
    const val THEME_BLACK = 4
    const val THEME_WOOD = 5
    const val THEME_PLUM = 6
    const val THEME_LINEN = 7
    const val THEME_PURE_WHITE = 8
    const val THEME_DARK_GREEN = 9

    const val MODE_NONE = 0
    const val MODE_SLIDE = 1
    const val MODE_CURL = 2
    const val MODE_VERTICAL = 4
    const val MODE_SCROLL = 5

    data class Theme(val id: Int, @androidx.annotation.StringRes val nameRes: Int, val paper: Int, val ink: Int, val surface: Int,
                     val texture: Int = 0, val dark: Boolean = false)

    val themes = listOf(
        Theme(THEME_PAPER, R.string.reader_theme_paper, 0xFFC9B282.toInt(), 0xFF302303.toInt(), 0xFFCCB992.toInt(), R.drawable.reader_texture_kraft),
        Theme(THEME_WHITE, R.string.reader_theme_white, 0xFFF6F6F6.toInt(), 0xFF262626.toInt(), Color.WHITE, R.drawable.reader_texture_white),
        Theme(THEME_NIGHT, R.string.reader_theme_night, 0xFF1A1A1A.toInt(), 0xFFDDDDDD.toInt(), 0xFF262626.toInt(), dark = true),
        Theme(THEME_BLACK, R.string.reader_theme_black, Color.BLACK, 0xFFCCCCCC.toInt(), 0xFF1A1A1A.toInt(), dark = true),
        Theme(THEME_GREEN, R.string.reader_theme_green, 0xFFD2E4D2.toInt(), 0xFF132013.toInt(), 0xFFE4EFE4.toInt()),
        Theme(THEME_WOOD, R.string.reader_theme_wood, 0xFFD2A679.toInt(), 0xFF302303.toInt(), 0xFFD5B890.toInt(), R.drawable.reader_texture_wood),
        Theme(THEME_PLUM, R.string.reader_theme_plum, 0xFFFFE4E1.toInt(), 0xFF330500.toInt(), 0xFFFFF1F0.toInt()),
        Theme(THEME_LINEN, R.string.reader_theme_linen, 0xFFFAF0E6.toInt(), 0xFF302303.toInt(), 0xFFFCF7F2.toInt()),
        Theme(THEME_PURE_WHITE, R.string.reader_theme_pure_white, Color.WHITE, Color.BLACK, 0xFFF6F6F6.toInt()),
        Theme(THEME_DARK_GREEN, R.string.reader_theme_dark_green, Color.BLACK, 0xFF39B991.toInt(), 0xFF01110F.toInt(), dark = true)
    )

    val turns = listOf(MODE_NONE to R.string.reader_turn_none, MODE_CURL to R.string.reader_turn_curl, MODE_SLIDE to R.string.reader_turn_horizontal,
        MODE_VERTICAL to R.string.reader_turn_vertical, MODE_SCROLL to R.string.reader_turn_scroll)
    val fonts = listOf("serif" to R.string.reader_font_serif, "sans-serif" to R.string.reader_font_sans, "monospace" to R.string.reader_font_monospace)
    fun palette(id: Int): Theme = themes.firstOrNull { it.id == id } ?: themes.first()

    private const val SP = "reader"
    private fun prefs(context: Context) = context.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun fontSize(context: Context): Int =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("fontSize", 19)

    fun setFontSize(context: Context, size: Int) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("fontSize", size.coerceIn(14, 32)).apply()
    }

    fun margin(context: Context): Int =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("margin", 25)

    fun setMargin(context: Context, value: Int) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("margin", value.coerceIn(0, 100)).apply()
    }

    fun lineSpacing(context: Context): Int =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("lineSpacing", 46)

    fun setLineSpacing(context: Context, value: Int) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("lineSpacing", value.coerceIn(0, 100)).apply()
    }

    fun theme(context: Context): Int =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("theme", THEME_PAPER)

    fun setTheme(context: Context, theme: Int) {
        prefs(context).edit().apply {
            putInt("theme", theme)
            if (!palette(theme).dark) putInt("dayTheme", theme)
        }.apply()
    }

    fun dayTheme(context: Context): Int = prefs(context).getInt("dayTheme", THEME_PAPER)

    fun turnMode(context: Context): Int =
        prefs(context).getInt("turnMode", MODE_CURL).let { saved ->
            if (saved == 3) MODE_SLIDE else turns.firstOrNull { it.first == saved }?.first ?: MODE_CURL
        }

    fun setTurnMode(context: Context, mode: Int) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("turnMode", mode).apply()
    }

    fun background(theme: Int): Int = palette(theme).paper
    fun ink(theme: Int): Int = palette(theme).ink
    fun chrome(theme: Int): Int = palette(theme).surface
    fun softInk(theme: Int): Int = androidx.core.graphics.ColorUtils.blendARGB(ink(theme), background(theme), 0.34f)
    fun backgroundHex(theme: Int): String = "#%06X".format(background(theme) and 0xFFFFFF)
    fun inkHex(theme: Int): String = "#%06X".format(ink(theme) and 0xFFFFFF)
    fun fontFamily(context: Context) = prefs(context).getString("fontFamily", fonts.first().first) ?: fonts.first().first
    fun setFontFamily(context: Context, family: String) { prefs(context).edit().putString("fontFamily", family).apply() }
    fun doubleColumns(context: Context) = prefs(context).getBoolean("doubleColumns", false)
    fun setDoubleColumns(context: Context, value: Boolean) { prefs(context).edit().putBoolean("doubleColumns", value).apply() }
    fun brightness(context: Context) = prefs(context).getInt("brightness", 60)
    fun setBrightness(context: Context, value: Int) { prefs(context).edit().putInt("brightness", value).apply() }
    fun followsBrightness(context: Context) = prefs(context).getBoolean("systemBrightness", true)
    fun setFollowsBrightness(context: Context, value: Boolean) { prefs(context).edit().putBoolean("systemBrightness", value).apply() }
    fun autoSpeed(context: Context) = prefs(context).getInt("autoSpeed", 30)
    fun setAutoSpeed(context: Context, value: Int) { prefs(context).edit().putInt("autoSpeed", value).apply() }
    fun showChapter(context: Context) = prefs(context).getBoolean("showChapter", true)
    fun setShowChapter(context: Context, value: Boolean) { prefs(context).edit().putBoolean("showChapter", value).apply() }
    fun showFooter(context: Context) = prefs(context).getBoolean("showFooter", true)
    fun setShowFooter(context: Context, value: Boolean) { prefs(context).edit().putBoolean("showFooter", value).apply() }
    fun volumeTurns(context: Context) = prefs(context).getBoolean("volumeTurns", false)
    fun setVolumeTurns(context: Context, value: Boolean) { prefs(context).edit().putBoolean("volumeTurns", value).apply() }
}

package com.cloudin.reader

import android.content.Context
import android.graphics.Color

object ReaderPrefs {
    const val THEME_PAPER = 0
    const val THEME_WHITE = 1
    const val THEME_NIGHT = 2
    const val THEME_GREEN = 3

    const val MODE_NONE = 0
    const val MODE_SLIDE = 1
    const val MODE_CURL = 2
    const val MODE_FADE = 3

    private const val SP = "reader"

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
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("theme", theme).apply()
    }

    fun turnMode(context: Context): Int =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("turnMode", MODE_CURL)

    fun setTurnMode(context: Context, mode: Int) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("turnMode", mode).apply()
    }

    fun background(theme: Int): Int = when (theme) {
        THEME_WHITE -> Color.parseColor("#FFFFFF")
        THEME_NIGHT -> Color.parseColor("#212832")
        THEME_GREEN -> Color.parseColor("#CCF0CF")
        else -> Color.parseColor("#D9C7A4")
    }

    fun ink(theme: Int): Int = when (theme) {
        THEME_WHITE -> Color.parseColor("#1A1A1A")
        THEME_NIGHT -> Color.parseColor("#B6B6BA")
        THEME_GREEN -> Color.parseColor("#24322A")
        else -> Color.parseColor("#302303")
    }

    fun softInk(theme: Int): Int = when (theme) {
        THEME_WHITE -> Color.parseColor("#8C8C8C")
        THEME_NIGHT -> Color.parseColor("#75757A")
        THEME_GREEN -> Color.parseColor("#6E8274")
        else -> Color.parseColor("#7B6642")
    }

    fun chrome(theme: Int): Int = when (theme) {
        THEME_WHITE -> Color.parseColor("#FFFFFF")
        THEME_NIGHT -> Color.parseColor("#2A303A")
        THEME_GREEN -> Color.parseColor("#C1E7C5")
        else -> Color.parseColor("#E3D3B2")
    }

    fun backgroundHex(theme: Int): String = when (theme) {
        THEME_WHITE -> "#FFFFFF"
        THEME_NIGHT -> "#212832"
        THEME_GREEN -> "#CCF0CF"
        else -> "#D9C7A4"
    }

    fun inkHex(theme: Int): String = when (theme) {
        THEME_WHITE -> "#1A1A1A"
        THEME_NIGHT -> "#B6B6BA"
        THEME_GREEN -> "#24322A"
        else -> "#2B2A28"
    }

    /** 主题预览色，用于设置里的小圆点。 */
    fun swatchColor(theme: Int): Int = when (theme) {
        THEME_WHITE -> Color.parseColor("#FFFFFF")
        THEME_NIGHT -> Color.parseColor("#212832")
        THEME_GREEN -> Color.parseColor("#CCF0CF")
        else -> Color.parseColor("#E7DCC3")
    }
}

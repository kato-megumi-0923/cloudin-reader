package com.cloudin.reader

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Calendar

/**
 * 夜间模式：状态、时间区间判断，以及把状态落到阅读主题与书架主题（皮肤）上。
 *
 * 三种触发方式互斥：跟随系统 > 自动时间段 > 手动（以当前阅读主题为准）。
 * 时间区间跨零点（如 20:00 - 07:00）按「或」判断。
 */
internal object NightMode {

    private const val PREFS = "night_mode"
    private const val KEY_AUTO = "auto"
    private const val KEY_START = "start"
    private const val KEY_END = "end"
    private const val KEY_FOLLOW = "follow_system"

    private const val DEFAULT_START = 20 * 60
    private const val DEFAULT_END = 7 * 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun auto(context: Context) = prefs(context).getBoolean(KEY_AUTO, false)
    fun setAuto(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun followSystem(context: Context) = prefs(context).getBoolean(KEY_FOLLOW, false)
    fun setFollowSystem(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOLLOW, value).apply()
    }

    fun startMinute(context: Context) = prefs(context).getInt(KEY_START, DEFAULT_START)
    fun setStartMinute(context: Context, minute: Int) {
        prefs(context).edit().putInt(KEY_START, minute.coerceIn(0, 24 * 60 - 1)).apply()
    }

    fun endMinute(context: Context) = prefs(context).getInt(KEY_END, DEFAULT_END)
    fun setEndMinute(context: Context, minute: Int) {
        prefs(context).edit().putInt(KEY_END, minute.coerceIn(0, 24 * 60 - 1)).apply()
    }

    fun timeText(minute: Int) = String.format("%02d:%02d", minute / 60, minute % 60)

    /** 现在是否应该处于夜间。 */
    fun isNight(context: Context): Boolean {
        if (followSystem(context) && Build.VERSION.SDK_INT >= 29) {
            return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        if (auto(context)) {
            val now = Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            val start = startMinute(context)
            val end = endMinute(context)
            if (start == end) return false
            return if (start < end) now in start until end else now >= start || now < end
        }
        return ReaderPrefs.palette(ReaderPrefs.theme(context)).dark
    }

    /** 夜间阅读底固定纯黑，书架主题固定炫酷黑（与主题页的「炫酷黑」是同一个）。 */
    fun nightTheme(context: Context): Int = ReaderPrefs.THEME_BLACK

    /**
     * 把当前应该处于的状态落到阅读主题与书架主题上。返回是否真的改动过
     * （改动过调用方需要刷新界面）。
     */
    fun apply(context: Context): Boolean {
        // 只有「自动」或「跟随系统」开启时才由这里接管；两个都关时，夜间与否完全交给
        // 阅读主题本身，避免用户手动切回日间后被反复拽回炫酷黑。
        if (!auto(context) && !followSystem(context)) return false
        val night = isNight(context)
        val current = ReaderPrefs.theme(context)
        val isDark = ReaderPrefs.palette(current).dark
        if (night) {
            val target = nightTheme(context)
            val themeChanged = current != target
            if (themeChanged) ReaderPrefs.setTheme(context, target)
            // 皮肤还没到炫酷黑就一并修（皮肤切换必须走 SkinSync，它负责记录进夜间前的主题）
            if (themeChanged || !SkinStore.load(context).isCoolBlack) {
                SkinSync.onReaderTheme(context, true)
                return true
            }
            return false
        }
        if (isDark) {
            ReaderPrefs.setTheme(context, ReaderPrefs.dayTheme(context))
            SkinSync.onReaderTheme(context, false)
            return true
        }
        return false
    }


    /** 手动退出夜间：恢复日间主题与进夜间前的主题皮肤。 */
    fun exitNight(context: Context) {
        if (!ReaderPrefs.palette(ReaderPrefs.theme(context)).dark) return
        ReaderPrefs.setTheme(context, ReaderPrefs.dayTheme(context))
        SkinSync.onReaderTheme(context, false)
    }

    /** 设置页右侧的状态文案。 */
    fun label(context: Context): String = when {
        followSystem(context) -> "跟随系统"
        auto(context) -> "自动 " + timeText(startMinute(context)) + "-" + timeText(endMinute(context))
        ReaderPrefs.palette(ReaderPrefs.theme(context)).dark -> "已开启"
        else -> "已关闭"
    }
}

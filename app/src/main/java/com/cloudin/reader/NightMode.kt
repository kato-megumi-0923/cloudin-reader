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
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONFIG_VERSION = "config_version"

    /** 配置版本：v1 是拆分前的旧存档，v2 起「自动切换」默认全关。 */
    private const val CONFIG_VERSION = 2

    // 三个开关的默认值都显式写成 false：默认不做任何自动切换。
    private const val DEFAULT_ENABLED = false
    private const val DEFAULT_AUTO = false
    private const val DEFAULT_FOLLOW = false

    private const val DEFAULT_START = 20 * 60
    private const val DEFAULT_END = 7 * 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 总开关：关闭时下面两种触发方式都不生效，但会记住上次的选择。 */
    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun auto(context: Context) = prefs(context).getBoolean(KEY_AUTO, DEFAULT_AUTO)
    fun setAuto(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun followSystem(context: Context) = prefs(context).getBoolean(KEY_FOLLOW, DEFAULT_FOLLOW)
    fun setFollowSystem(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOLLOW, value).apply()
    }

    /**
     * 一次性把旧存档里的自动切换清掉（老版本/试用时可能存了 auto=true）。
     * 升级后回到「夜间模式全部关闭」的初始态；如果当前正停在夜间，一并退回日间，
     * 免得开关显示关闭但界面还是黑的。幂等，多次调用无副作用。
     */
    fun ensureConfigCurrent(context: Context) {
        val store = prefs(context)
        if (store.getInt(KEY_CONFIG_VERSION, 1) >= CONFIG_VERSION) return
        val wasEnabled = store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        store.edit()
            .putBoolean(KEY_ENABLED, DEFAULT_ENABLED)
            .putBoolean(KEY_AUTO, DEFAULT_AUTO)
            .putBoolean(KEY_FOLLOW, DEFAULT_FOLLOW)
            .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
            .apply()
        if (wasEnabled) exitNight(context)
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

    /** 夜间阅读底用「夜间」（冷蓝灰），不再用纯黑的「雅黑」；书架主题仍切炫酷黑。 */
    fun nightTheme(context: Context): Int = ReaderPrefs.THEME_NIGHT

    /**
     * 把当前应该处于的状态落到阅读主题与书架主题上。返回是否真的改动过
     * （改动过调用方需要刷新界面）。
     */
    fun apply(context: Context): Boolean {
        // 只有总开关打开、且选了某一种触发方式时才由这里接管；否则夜间与否完全交给
        // 阅读主题本身，避免用户手动切回日间后被反复拽回炫酷黑。
        if (!enabled(context)) {
            if (!auto(context) && !followSystem(context)) {
                // 快照来自用户手动切的夜间时不要还原：那个「夜间」应该延续到书架，
                // 否则从阅读页切换夜间再退出，书架又被拽回原来那张皮肤。
                if (SkinSync.snapshotIsManual(context)) return false
                // 其余情况才补这一刀：快照说明皮肤曾被自动切黑又没还原
                // （老版本 exitNight 的漏洞，或阅读主题被手动改回日间）。
                return SkinSync.restoreNightSnapshot(context)
            }
            return false
        }
        if (!auto(context) && !followSystem(context)) return false
        val night = isNight(context)
        val current = ReaderPrefs.theme(context)
        val isDark = ReaderPrefs.palette(current).dark
        if (night) {
            val target = nightTheme(context)
            val themeChanged = current != target
            if (themeChanged) ReaderPrefs.setTheme(context, target)
            // 皮肤还没到炫酷黑就一并修（皮肤切换必须走 SkinSync，它负责记录进夜间前的主题）
            if (themeChanged || !SkinStore.load(context).isNightDark) {
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
        if (ReaderPrefs.palette(ReaderPrefs.theme(context)).dark) {
            ReaderPrefs.setTheme(context, ReaderPrefs.dayTheme(context))
        }
        // 皮肤要无条件按快照还原：阅读主题可能早被手动改回日间，
        // 只按「主题是不是深色」来决定还原皮肤，会留下一个黑色皮肤永远退不回来。
        SkinSync.restoreNightSnapshot(context)
    }

    /** 设置页右侧的状态文案。 */
    fun label(context: Context): String = when {
        !enabled(context) -> "已关闭"
        followSystem(context) -> "跟随系统"
        auto(context) -> "自动 " + timeText(startMinute(context)) + "-" + timeText(endMinute(context))
        else -> "已开启"
    }
}

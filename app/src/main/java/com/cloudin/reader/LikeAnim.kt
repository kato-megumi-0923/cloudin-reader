package com.cloudin.reader

import android.content.Context

/**
 * 书架小心心的点击动效。三种风格共用同一套「弹跳 + 粒子」引擎，只是配方不同。
 *
 * 默认 [LOVE]：保持以往行为（只有图标切换和一点回弹），不主动往书架上撒粒子。
 */
internal enum class LikeAnim(val label: String) {
    /** 只有轻微回弹，几乎没有额外绘制。 */
    LOVE("心爱"),

    /** 两圈波纹 + 红点向上飘散。 */
    BEAT("心动"),

    /** 冲击波 + 放射火花；取消时碎成小块下落。 */
    SHATTER("心碎");

    fun save(context: Context) {
        preferences(context).edit().putString(KEY, name).apply()
    }

    companion object {
        private const val KEY = "like_anim"

        fun load(context: Context): LikeAnim {
            val saved = preferences(context).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: LOVE
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    }
}

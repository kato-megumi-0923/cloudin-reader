package com.cloudin.reader

import com.cloudin.refract.GlassView

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children

internal enum class GlassStyle(val label: Int) {
    LIQUID(R.string.glass_style_liquid),
    FROSTED(R.string.glass_style_frosted);

    fun save(context: Context) {
        preferences(context).edit().putString(KEY, name).apply()
    }

    /**
     * 铺到界面上的所有玻璃。
     *
     * 液态和磨砂都走同一个 GlassView：磨砂只是打开它的 `frosted`（内部把折射归零、
     * 模糊抬到 ≥13dp）。不再需要保存/还原第三方控件的参数。
     */
    fun applyTo(view: View) {
        when (view) {
            is ShelfTabBar -> {
                view.glassStyle = this
                return
            }
            is GlassView -> view.frosted = this == FROSTED
        }
        if (view is ViewGroup) view.children.forEach { applyTo(it) }
    }

    companion object {
        private const val KEY = "glass_style"

        fun load(context: Context): GlassStyle {
            val saved = preferences(context).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: LIQUID
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    }
}

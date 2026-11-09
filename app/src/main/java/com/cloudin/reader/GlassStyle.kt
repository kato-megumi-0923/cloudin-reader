package com.cloudin.reader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.example.liquidglass.LiquidGlassView

internal enum class GlassStyle(val label: Int) {
    LIQUID(R.string.glass_style_liquid),
    FROSTED(R.string.glass_style_frosted);

    fun save(context: Context) {
        preferences(context).edit().putString(KEY, name).apply()
    }

    fun applyTo(view: View) {
        when (view) {
            is ShelfTabBar -> {
                view.glassStyle = this
                return
            }
            is LiquidGlassView -> applyToGlass(view)
            is AgslGlassView -> view.frosted = this == FROSTED
        }
        if (view is ViewGroup) view.children.forEach { applyTo(it) }
    }

    private fun applyToGlass(view: LiquidGlassView) {
        val original = view.getTag(R.id.original_glass_style) as? GlassParameters
        if (this == FROSTED && original == null) {
            view.setTag(R.id.original_glass_style, GlassParameters(
                view.useShaderPipeline,
                view.enableChromaticAberration,
                view.enableChromaticDispersion,
                view.enableEdgeHighlight,
                view.blurAmount,
                view.saturation,
                view.glassTint
            ))
            view.enableChromaticAberration = false
            view.enableChromaticDispersion = false
            view.enableEdgeHighlight = false
            view.useShaderPipeline = false
            view.blurAmount = maxOf(view.blurAmount, 0.42f)
            view.saturation = 105f
            view.glassTint = 0x48FFFFFF
            view.invalidate()
        } else if (this == LIQUID && original != null) {
            view.useShaderPipeline = original.shaderPipeline
            view.enableChromaticAberration = original.aberration
            view.enableChromaticDispersion = original.dispersion
            view.enableEdgeHighlight = original.edgeHighlight
            view.blurAmount = original.blurAmount
            view.saturation = original.saturation
            view.glassTint = original.tint
            view.setTag(R.id.original_glass_style, null)
            view.invalidate()
        }
    }

    private data class GlassParameters(
        val shaderPipeline: Boolean,
        val aberration: Boolean,
        val dispersion: Boolean,
        val edgeHighlight: Boolean,
        val blurAmount: Float,
        val saturation: Float,
        val tint: Int
    )

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

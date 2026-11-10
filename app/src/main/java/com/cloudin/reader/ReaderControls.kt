package com.cloudin.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.readerDp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.readerPill(color: Int, radius: Int = 24) = GradientDrawable().apply {
    cornerRadius = readerDp(radius).toFloat()
    setColor(color)
}

internal fun Context.readerLabel(text: String, color: Int, size: Float = 14f) = TextView(this).apply {
    this.text = text
    textSize = size
    gravity = Gravity.CENTER
    setTextColor(color)
}

internal fun Context.readerButton(text: String, theme: Int, backdrop: View, action: () -> Unit) =
    GlassActionButton(this).apply {
        this.text = text
        textView.textSize = 13f
        textView.setPadding(readerDp(3), 0, readerDp(3), 0)
        textView.setTextColor(ReaderPrefs.ink(theme))
        tintColor = ReaderPrefs.chrome(theme)
        backdropSource = backdrop
        usePopupOptics()
        frosted = GlassStyle.load(context) == GlassStyle.FROSTED
        setGlassClickListener { action() }
    }

internal fun Context.readerSegments(
    labels: List<String>, initial: Int, theme: Int, backdrop: View, label: String,
    vertical: Boolean = false, onSelect: (Int) -> Unit
): View {
    val ink = ReaderPrefs.ink(theme)
    val dark = ReaderPrefs.palette(theme).dark
    val track = FrameLayout(this).apply {
        contentDescription = label
        background = readerPill(if (dark) 0x20FFFFFF else 0x1E473315)
        setPadding(readerDp(2), readerDp(2), readerDp(2), readerDp(2))
    }
    val lens = AgslGlassView(this).apply {
        useControlOptics(24f)
        usePopupOptics()
        tintColor = if (dark) 0xFF777777.toInt() else Color.WHITE
        tintAlpha = if (dark) 0.54f else 0.46f
        backdropSource = backdrop
        frosted = GlassStyle.load(context) == GlassStyle.FROSTED
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    track.addView(lens, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
    val row = LinearLayout(this).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
    track.addView(row, FrameLayout.LayoutParams(-1, -1))
    var selected = initial.coerceIn(labels.indices)
    val buttons = labels.map { item ->
        readerLabel(item, ink, 14f).also {
            val size = if (vertical) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f)
            row.addView(it, size)
        }
    }
    fun sync(animate: Boolean) {
        buttons.forEachIndexed { index, button ->
            button.isSelected = index == selected
            button.setTypeface(null, if (button.isSelected) Typeface.BOLD else Typeface.NORMAL)
            button.alpha = if (button.isSelected) 1f else 0.78f
        }
        val button = buttons[selected]
        if (lens.layoutParams.width != button.width || lens.layoutParams.height != button.height) {
            lens.layoutParams = lens.layoutParams.apply { width = button.width; height = button.height }
        }
        val x = button.left.toFloat()
        val y = button.top.toFloat()
        lens.animate().cancel()
        if (animate) {
            lens.animate().translationX(x).translationY(y).setDuration(240)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                .setUpdateListener { lens.invalidate() }.start()
        } else {
            lens.translationX = x
            lens.translationY = y
        }
    }
    row.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob || lens.width == 0) sync(false)
    }
    buttons.forEachIndexed { index, button ->
        button.isFocusable = true
        button.setGlassClickListener(lens) {
            if (index != selected) {
                selected = index
                sync(true)
                onSelect(index)
            }
        }
    }
    sync(false)
    return track
}

internal fun Context.readerTurnOptions(theme: Int, backdrop: View, onChange: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(readerSegments(listOf("单列", "双列"), if (ReaderPrefs.doubleColumns(context)) 1 else 0,
            theme, backdrop, "阅读排版") {
            ReaderPrefs.setDoubleColumns(context, it == 1)
            onChange()
        }, LinearLayout.LayoutParams(-1, readerDp(48)))
        val turns = ReaderPrefs.turns
        addView(readerSegments(turns.map { it.second }, turns.indexOfFirst { it.first == ReaderPrefs.turnMode(context) },
            theme, backdrop, "翻页方式") {
            ReaderPrefs.setTurnMode(context, turns[it].first)
            onChange()
        }, LinearLayout.LayoutParams(-1, readerDp(48)).apply { topMargin = readerDp(10) })
    }

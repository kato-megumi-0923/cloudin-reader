package com.cloudin.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentDialog

/** 设置类页面共用的构件：卡片、行、单选弹窗。 */

internal fun Context.settingCard(dark: Boolean): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        cornerRadius = readerDp(22).toFloat()
        setColor(if (dark) 0x2EFFFFFF else 0xF2FFFFFF.toInt())
        setStroke(readerDp(1), if (dark) 0x1FFFFFFF else 0x14000000)
    }
}

/**
 * 一行设置：[label] 居左，[value] 与箭头居右。行之间自动画分割线。
 * [clickable] 传 false 时整行不可点（开关 / 时间这类自带控件的行）。
 */
internal fun LinearLayout.addSettingRow(
    dark: Boolean,
    label: String,
    value: String? = null,
    clickable: Boolean = true,
    leading: View? = null,
    trailing: View? = null,
    showArrow: Boolean = true,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val ctx = context
    if (childCount > 0) {
        addView(View(ctx).apply {
            setBackgroundColor(if (dark) 0x26FFFFFF else 0x11000000)
        }, LinearLayout.LayoutParams(-1, ctx.readerDp(1)).apply {
            marginStart = ctx.readerDp(18)
            marginEnd = ctx.readerDp(18)
        })
    }

    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.readerDp(18), 0, ctx.readerDp(16), 0)
        if (clickable) {
            isClickable = true
            isFocusable = true
            background = ctx.selectableItemBackground()
        }
    }
    if (leading != null) {
        row.addView(leading, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = ctx.readerDp(14)
        })
    }
    row.addView(ctx.readerLabel(label, SkinTheme.title(dark), 15f).apply {
        gravity = Gravity.CENTER_VERTICAL
    }, LinearLayout.LayoutParams(0, -1, 1f))

    if (!value.isNullOrEmpty()) {
        row.addView(ctx.readerLabel(value, SkinTheme.soft(dark), 13f).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-2, -1))
    }
    if (trailing != null) {
        row.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = ctx.readerDp(10)
        })
    }
    if (onClick != null) {
        if (showArrow) row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_profile_chevron)
            imageTintList = ColorStateList.valueOf(SkinTheme.soft(dark))
            alpha = 0.7f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ctx.readerDp(16), ctx.readerDp(16)).apply {
            marginStart = ctx.readerDp(8)
        })
        row.setOnClickListener { onClick() }
    }

    addView(row, LinearLayout.LayoutParams(-1, ctx.readerDp(56)))
    return row
}

internal fun Context.selectableItemBackground(): Drawable? {
    val out = TypedValue()
    return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
        getDrawable(out.resourceId)
    } else null
}

/**
 * 单选弹窗：玻璃卡片 + 选项列表，点选项即生效、点卡片外关闭。
 *
 * 注意 [backdrop] 必须是整页根视图 —— 指向页面内部的背景层会让 AGSL 在 RenderThread
 * 里崩溃（SIGSEGV）。
 */
internal fun Context.showSettingChoice(
    dark: Boolean,
    backdrop: View,
    title: String,
    labels: List<String>,
    selected: Int,
    onPick: (Int) -> Unit
) {
    val dialog = ComponentDialog(this)
    val content = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_group_card, null)
    content.findViewById<TextView>(R.id.cardTitle).apply {
        text = title
        setTextColor(SkinTheme.title(dark))
    }
    val rows = content.findViewById<LinearLayout>(R.id.cardRows)
    val ink = SkinTheme.title(dark)
    labels.forEachIndexed { index, label ->
        val row = android.view.LayoutInflater.from(this).inflate(R.layout.row_dialog_action, rows, false)
        row.findViewById<TextView>(R.id.cardRowText).apply {
            text = if (index == selected) getString(R.string.checked_choice_label, label) else label
            setTextColor(ink)
        }
        row.setOnClickListener {
            dialog.dismiss()
            onPick(index)
        }
        rows.addView(row)
    }
    content.findViewById<View>(R.id.cardCancel).visibility = View.GONE
    rows.setPadding(0, 0, 0, readerDp(10))

    content.background = GradientDrawable().apply {
        cornerRadius = readerDp(26).toFloat()
        setColor(0x2EFFFFFF)
    }
    val skin = SkinStore.load(this)
    val glass = AgslGlassView(this).apply {
        cornerRadius = readerDp(26).toFloat()
        blurRadius = readerDp(11).toFloat()
        tintColor = if (skin.pale) 0xFFDDE4ED.toInt() else Color.WHITE
        tintAlpha = if (skin.pale) 0.46f else 0.10f
        dispersion = 0.16f
        usePopupOptics()
    }
    glass.addView(content, FrameLayout.LayoutParams(-1, -2))
    dialog.setContentView(glass)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(if (dark) 0.20f else 0.06f)
    }
    dialog.show()
    val width = minOf(
        readerDp(345),
        (resources.displayMetrics.widthPixels - readerDp(32)).coerceAtLeast(1)
    )
    dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.window?.setGravity(Gravity.CENTER)
    glass.backdropSource = backdrop
    GlassStyle.load(this).applyTo(glass)
}

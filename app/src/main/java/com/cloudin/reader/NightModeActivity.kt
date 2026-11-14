package com.cloudin.reader

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentDialog
import androidx.core.view.ViewCompat

/**
 * 夜间模式设置。
 *
 * 深灰配色 / 自动切换（时间段）/ 跟随系统三者会互相影响，改动后立即落盘并
 * [NightMode.apply] 到阅读主题与书架主题；本页随后 recreate，让背景与文字跟着换。
 */
class NightModeActivity : BaseActivity() {

    override val systemBarDark: Boolean get() = SkinStore.load(this).dark

    private lateinit var backdrop: View
    private lateinit var pageRoot: View
    private lateinit var list: LinearLayout
    private lateinit var autoDot: RadioDot
    private lateinit var followDot: RadioDot
    private lateinit var timesRow: LinearLayout
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = SkinStore.load(this).dark
        backdrop = View(this).also { SkinStore.applyTo(this, it, SkinStore.load(this)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ink = SkinTheme.title(dark)

        val heading = FrameLayout(this)
        heading.addView(readerLabel("夜间模式", ink, 19f).apply {
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        heading.addView(glassBackButton(ink, backdrop) { finish() },
            FrameLayout.LayoutParams(readerDp(44), readerDp(44), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = readerDp(14) })
        column.addView(heading, LinearLayout.LayoutParams(-1, readerDp(58)))

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(14), readerDp(4), readerDp(14), readerDp(28))
        }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        pageRoot = FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(pageRoot)
        insetContent(column)
        ViewCompat.requestApplyInsets(column)
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        val card = settingCard(dark)
        val autoOn = NightMode.auto(this)
        val followOn = NightMode.followSystem(this)

        // 单选组：按时间区间 / 跟随系统，二者互斥；再点已选中的可取消
        autoDot = RadioDot(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(autoOn)
        }
        card.addSettingRow(dark, "自定义夜间启用时间区间", leading = autoDot, showArrow = false) {
            toggleTimeRange()
        }

        timesRow = buildTimes().apply {
            visibility = if (autoOn) View.VISIBLE else View.GONE
            alpha = if (autoOn) 1f else 0f
        }
        card.addView(timesRow, LinearLayout.LayoutParams(-1, -2))

        followDot = RadioDot(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(followOn)
        }
        card.addSettingRow(dark, "跟随系统", leading = followDot, showArrow = false) {
            toggleFollowSystem()
        }

        card.addView(readerLabel(hint(), SkinTheme.soft(dark), 12f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(readerDp(52), 0, readerDp(18), readerDp(14))
        }, LinearLayout.LayoutParams(-1, -2))

        list.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildTimes(): LinearLayout {
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(readerDp(52), 0, readerDp(16), readerDp(12))
        }
        times.addView(timeButton(NightMode.startMinute(this), isStart = true) { picked ->
            NightMode.setStartMinute(this, picked)
            applyAndRefresh()
        }, LinearLayout.LayoutParams(readerDp(96), readerDp(40)))
        times.addView(readerLabel("至", SkinTheme.soft(dark), 13f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(readerDp(34), -1))
        times.addView(timeButton(NightMode.endMinute(this), isStart = false) { picked ->
            NightMode.setEndMinute(this, picked)
            applyAndRefresh()
        }, LinearLayout.LayoutParams(readerDp(96), readerDp(40)))
        return times
    }

    /** 按时间区间：选中即开启自动，同时取消跟随系统；再点取消则两个都不启用。 */
    private fun toggleTimeRange() {
        val next = !NightMode.auto(this)
        NightMode.setAuto(this, next)
        if (next) NightMode.setFollowSystem(this, false)
        if (!next && !NightMode.followSystem(this)) NightMode.exitNight(this)
        val changed = NightMode.apply(this)
        syncChoices()
        // 只有底色真的变了才重建页面，否则留着让圆点动画播完
        if (changed) list.postDelayed({ recreate() }, 260)
    }

    private fun toggleFollowSystem() {
        val next = !NightMode.followSystem(this)
        NightMode.setFollowSystem(this, next)
        if (next) NightMode.setAuto(this, false)
        if (!next && !NightMode.auto(this)) NightMode.exitNight(this)
        val changed = NightMode.apply(this)
        syncChoices()
        if (changed) list.postDelayed({ recreate() }, 260)
    }

    /** 原地更新圆点与时间区间的显隐（不重建视图，保证动画连续）。 */
    private fun syncChoices() {
        if (!::autoDot.isInitialized) return
        autoDot.chosen = NightMode.auto(this)
        followDot.chosen = NightMode.followSystem(this)

        val showTimes = NightMode.auto(this)
        timesRow.animate().cancel()
        if (showTimes) {
            timesRow.visibility = View.VISIBLE
            timesRow.animate().alpha(1f).setDuration(180).start()
        } else if (timesRow.visibility == View.VISIBLE) {
            timesRow.animate().alpha(0f).setDuration(160).withEndAction {
                if (!NightMode.auto(this)) timesRow.visibility = View.GONE
            }.start()
        }
    }

    private fun hint(): String {
        val base = "支持 Android 9+ 系统，部分手机可能不支持"
        return if (Build.VERSION.SDK_INT < 29) "$base（当前系统低于 Android 9，此项不生效）" else base
    }

    private fun timeButton(minute: Int, isStart: Boolean, onPick: (Int) -> Unit): View =
        GlassActionButton(this).apply {
            text = NightMode.timeText(minute)
            textView.setTextColor(SkinTheme.title(dark))
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            // 背景源必须是「不包含自己」的背景层：指向页面根视图会让 RenderNode 递归，
            // 在 RenderThread 里直接 SIGSEGV
            backdropSource = backdrop
            frosted = GlassStyle.load(context) == GlassStyle.FROSTED
            useControlOptics(20f)
            setGlassClickListener {
                showTimePicker(isStart, minute, onPick)
            }
        }

    /** 滚轮式时间选择：标题 + 时/分两列 + 右下「取消 / 确定」，样式对齐参考图。 */
    private fun showTimePicker(isStart: Boolean, initial: Int, onPick: (Int) -> Unit) {
        var picked = initial
        val dialog = ComponentDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(readerDp(22), readerDp(20), readerDp(18), readerDp(10))
        }
        content.addView(readerLabel(if (isStart) "开始时间" else "结束时间", SkinTheme.title(dark), 17f)
            .apply { gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2))
        content.addView(timeWheelRow(initial, dark) { picked = it },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = readerDp(4)
            })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(timeAction("取消", SkinTheme.soft(dark)) { dialog.dismiss() })
        actions.addView(timeAction("确定", 0xFFD8453F.toInt()) {
            dialog.dismiss()
            onPick(picked)
        })
        content.addView(actions, LinearLayout.LayoutParams(-1, readerDp(48)))

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
        glass.backdropSource = pageRoot
        GlassStyle.load(this).applyTo(glass)
    }

    private fun timeAction(label: String, color: Int, action: () -> Unit): View =
        readerLabel(label, color, 15f).apply {
            setPadding(readerDp(16), readerDp(10), readerDp(16), readerDp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun applyAndRefresh() {
        NightMode.apply(this)
        // 底色可能从浅变深（或反之），重建一次让背景与文字整体跟上
        recreate()
    }
}

package com.cloudin.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
    private lateinit var autoRow: LinearLayout
    private lateinit var followRow: LinearLayout
    private lateinit var masterSwitch: GlassSwitch
    private lateinit var hintView: View
    private lateinit var autoValueView: android.widget.TextView
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
        val on = NightMode.enabled(this)

        // 总开关：关掉后下面两种方式都灰掉，但会记住上次的选择
        masterSwitch = GlassSwitch(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(on)
            onCheckedChange = { toggleMaster(it) }
        }
        card.addSettingRow(dark, "自动切换夜间模式", clickable = false, trailing = masterSwitch)

        // 圆点保留上一次的选择（整行变淡），关闭时也能看出之前配的是什么
        autoDot = RadioDot(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(NightMode.auto(this@NightModeActivity))
        }
        autoRow = card.addSettingRow(
            dark, "自定义夜间启用时间区间", leading = autoDot, showArrow = false
        ) {
            if (NightMode.enabled(this@NightModeActivity)) toggleTimeRange()
        }.apply { alpha = if (on) 1f else DISABLED_ALPHA }

        // 关闭时在行尾显示上次配置的时间，打开时下面已经有两个时间按钮，就不再重复
        autoValueView = readerLabel(timeRangeText(), SkinTheme.soft(dark), 13f).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (on) View.GONE else View.VISIBLE
        }
        autoRow.addView(autoValueView, LinearLayout.LayoutParams(-2, -1))

        timesRow = buildTimes().apply {
            val visible = on && NightMode.auto(this@NightModeActivity)
            visibility = if (visible) View.VISIBLE else View.GONE
            alpha = if (visible) 1f else 0f
        }
        card.addView(timesRow, LinearLayout.LayoutParams(-1, -2))

        followDot = RadioDot(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(NightMode.followSystem(this@NightModeActivity))
        }
        followRow = card.addSettingRow(
            dark, "跟随系统", leading = followDot, showArrow = false
        ) {
            if (NightMode.enabled(this@NightModeActivity)) toggleFollowSystem()
        }.apply { alpha = if (on) 1f else DISABLED_ALPHA }

        hintView = readerLabel(hint(), SkinTheme.soft(dark), 12f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(readerDp(52), 0, readerDp(18), readerDp(14))
            alpha = if (on) 1f else DISABLED_ALPHA
        }
        card.addView(hintView, LinearLayout.LayoutParams(-1, -2))

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

    /** 总开关：打开时若还没有方式就默认选中时间区间；关掉则直接退出夜间。 */
    private fun toggleMaster(value: Boolean) {
        NightMode.setEnabled(this, value)
        if (value) {
            if (!NightMode.auto(this) && !NightMode.followSystem(this)) NightMode.setAuto(this, true)
        } else {
            NightMode.exitNight(this)
        }
        val changed = NightMode.apply(this)
        syncEnabledUi()
        if (changed) list.postDelayed({ recreate() }, 280)
    }

    /** 按时间区间：选中即开启自动，同时取消跟随系统；再点取消则两个都不启用。 */
    private fun toggleTimeRange() {
        val next = !NightMode.auto(this)
        NightMode.setAuto(this, next)
        if (next) NightMode.setFollowSystem(this, false)
        if (!next && !NightMode.followSystem(this)) {
            // 两个方式都取消了，总开关跟着关掉，避免「开着却没选方式」的中间态
            NightMode.setEnabled(this, false)
            NightMode.exitNight(this)
        }
        val changed = NightMode.apply(this)
        syncChoices()
        if (changed) list.postDelayed({ recreate() }, 280)
    }

    private fun toggleFollowSystem() {
        val next = !NightMode.followSystem(this)
        NightMode.setFollowSystem(this, next)
        if (next) NightMode.setAuto(this, false)
        if (!next && !NightMode.auto(this)) {
            NightMode.setEnabled(this, false)
            NightMode.exitNight(this)
        }
        val changed = NightMode.apply(this)
        syncChoices()
        if (changed) list.postDelayed({ recreate() }, 280)
    }

    private fun timeRangeText(): String =
        NightMode.timeText(NightMode.startMinute(this)) + " - " + NightMode.timeText(NightMode.endMinute(this))

    private fun syncEnabledUi() {
        if (!::autoRow.isInitialized) return
        val on = NightMode.enabled(this)
        val target = if (on) 1f else DISABLED_ALPHA
        autoRow.animate().alpha(target).setDuration(160).start()
        autoValueView.text = timeRangeText()
        autoValueView.visibility = if (on) View.GONE else View.VISIBLE
        followRow.animate().alpha(target).setDuration(160).start()
        hintView.animate().alpha(target).setDuration(160).start()
        syncChoices()
    }

    /** 原地更新圆点与时间区间（不重建视图，保证动画连续）。 */
    private fun syncChoices() {
        if (!::autoDot.isInitialized) return
        val on = NightMode.enabled(this)
        if (::masterSwitch.isInitialized) masterSwitch.checked = on
        autoDot.chosen = NightMode.auto(this)
        followDot.chosen = NightMode.followSystem(this)
        animateTimesVisible(on && NightMode.auto(this))
    }

    /** 时间区间用「高度 + 透明度」一起动，只做淡入淡出会让下面的行瞬间跳位，很生硬。 */
    private fun animateTimesVisible(show: Boolean) {
        val view = timesRow
        val showing = view.visibility == View.VISIBLE && view.height > 0
        if (show == showing) {
            view.alpha = if (show) 1f else 0f
            if (!show) view.visibility = View.GONE
            return
        }
        view.animate().cancel()
        val parentWidth = (view.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        val startHeight = if (view.visibility == View.VISIBLE) view.height else 0
        view.visibility = View.VISIBLE
        view.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val endHeight = if (show) view.measuredHeight else 0
        if (startHeight == endHeight) {
            view.alpha = if (show) 1f else 0f
            if (!show) view.visibility = View.GONE
            return
        }
        val anim = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = 240
            addUpdateListener {
                val h = it.animatedValue as Int
                view.layoutParams = view.layoutParams.apply { height = h }
                view.alpha = if (endHeight == 0) {
                    if (startHeight == 0) 0f else 1f - h.toFloat() / startHeight
                } else {
                    h.toFloat() / endHeight
                }
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.layoutParams = view.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                view.alpha = if (show) 1f else 0f
                if (!show) view.visibility = View.GONE
            }
        })
        anim.start()
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

    private companion object {
        /** 总开关关闭时下面两项的透明度。 */
        const val DISABLED_ALPHA = 0.38f
    }
}

package com.cloudin.reader

import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

        // 1. 夜间底用纯黑还是深灰
        val pure = GlassSwitch(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(NightMode.pureBlack(this@NightModeActivity))
            onCheckedChange = { on ->
                NightMode.setPureBlack(this@NightModeActivity, on)
                applyAndRefresh()
            }
        }
        card.addSettingRow(dark, "夜间使用纯黑配色", clickable = false, trailing = pure)

        // 2. 按时间自动切换
        val auto = GlassSwitch(this).apply {
            this.dark = this@NightModeActivity.dark
            bind(NightMode.auto(this@NightModeActivity))
            onCheckedChange = { on ->
                NightMode.setAuto(this@NightModeActivity, on)
                if (on) NightMode.setFollowSystem(this@NightModeActivity, false)
                applyAndRefresh()
            }
        }
        card.addSettingRow(dark, "自动切换夜间模式", clickable = false, trailing = auto)

        // 3. 时间段
        card.addSettingRow(dark, "自定义夜间启用时间区间", clickable = false)
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(readerDp(18), 0, readerDp(16), readerDp(12))
        }
        times.addView(timeButton(NightMode.startMinute(this)) { picked ->
            NightMode.setStartMinute(this, picked)
            applyAndRefresh()
        }, LinearLayout.LayoutParams(readerDp(96), readerDp(40)))
        times.addView(readerLabel("至", SkinTheme.soft(dark), 13f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(readerDp(34), -1))
        times.addView(timeButton(NightMode.endMinute(this)) { picked ->
            NightMode.setEndMinute(this, picked)
            applyAndRefresh()
        }, LinearLayout.LayoutParams(readerDp(96), readerDp(40)))
        card.addView(times, LinearLayout.LayoutParams(-1, -2))

        // 4. 跟随系统
        val follow = NightMode.followSystem(this)
        val dot = RadioDot(this).apply { chosen = follow }
        card.addSettingRow(dark, "跟随系统", clickable = false, trailing = dot).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val next = !NightMode.followSystem(this@NightModeActivity)
                NightMode.setFollowSystem(this@NightModeActivity, next)
                if (next) NightMode.setAuto(this@NightModeActivity, false)
                applyAndRefresh()
            }
        }

        // 5. 说明
        card.addView(readerLabel(hint(), SkinTheme.soft(dark), 12f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(readerDp(18), 0, readerDp(18), readerDp(14))
        }, LinearLayout.LayoutParams(-1, -2))

        list.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun hint(): String {
        val base = "支持 Android 9+ 系统，部分手机可能不支持"
        return if (Build.VERSION.SDK_INT < 29) "$base（当前系统低于 Android 9，此项不生效）" else base
    }

    private fun timeButton(minute: Int, onPick: (Int) -> Unit): View =
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
                TimePickerDialog(
                    this@NightModeActivity,
                    { _, hour, min ->
                        onPick(hour * 60 + min)
                    },
                    minute / 60, minute % 60, true
                ).show()
            }
        }

    private fun applyAndRefresh() {
        NightMode.apply(this)
        // 底色可能从浅变深（或反之），重建一次让背景与文字整体跟上
        recreate()
    }
}

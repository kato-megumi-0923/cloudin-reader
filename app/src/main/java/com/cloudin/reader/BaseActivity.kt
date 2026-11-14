package com.cloudin.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 所有页面的公共底座：系统栏基础设置、图标明暗、inset 样板都收在这里。
 *
 * 新增页面只要继承它，不用再写 window 设置、不用判断皮肤明暗、也不用重复的 inset 样板。
 * [systemBarDark] 默认 false —— 本项目多数页面固定极光浅底，需要深色图标；
 * 跟随皮肤背景的页面覆盖为 SkinStore.load(this).dark，阅读页覆盖为阅读主题。
 *
 * 页面自身状态变化导致底色明暗改变时（皮肤预览切换草稿、阅读主题切换），
 * 仍可在自己的 render()/applyChromeColors() 里调一次 Window.syncSystemBarIconTone()，
 * onResume 的统一刷新只是兜底，不会覆盖这些即时刷新。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 系统栏图标是否应按深色底处理（true = 浅色图标）。 */
    protected open val systemBarDark: Boolean get() = false

    /**
     * 皮肤变化时是否整页重建。设置类页面的背景、标题、卡片分别在 onCreate 与 rebuild 里取色，
     * 只在局部刷新会出现「深色背景 + 白卡片」这种混搭，所以干脆重建。阅读页不参与。
     */
    protected open val reloadsOnSkinChange: Boolean get() = false

    private val skinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (reloadsOnSkinChange && !isFinishing) recreate()
        }
    }
    private var skinReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.useAuroraSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (reloadsOnSkinChange && !skinReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, skinReceiver, IntentFilter(SkinStore.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            skinReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (skinReceiverRegistered) {
            unregisterReceiver(skinReceiver)
            skinReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        window.syncSystemBarIconTone(systemBarDark)
    }
}

/** 统一取「系统栏 + 刘海」inset 的样板；页面特有的布局差异留在 [onInsets] 里。 */
internal fun Activity.onSystemBarInsets(
    root: View,
    onInsets: (left: Int, top: Int, right: Int, bottom: Int) -> Unit
) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        onInsets(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/** 最常见形态：整个内容区四边让出系统栏。 */
internal fun Activity.insetContent(view: View) = onSystemBarInsets(view) { left, top, right, bottom ->
    view.setPadding(left, top, right, bottom)
}

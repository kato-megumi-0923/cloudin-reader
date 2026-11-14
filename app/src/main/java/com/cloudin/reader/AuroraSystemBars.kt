package com.cloudin.reader

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun Window.useAuroraSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}

/** 深色皮肤下把状态栏图标切成浅色，避免黑色图标压在深色背景上。 */
internal fun Window.syncSystemBarIconTone(darkBackground: Boolean) {
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = !darkBackground
        isAppearanceLightNavigationBars = !darkBackground
    }
}

package com.cloudin.reader

import android.view.View
import android.view.animation.DecelerateInterpolator

internal fun View.animatePageEntry(forward: Boolean) {
    animate().cancel()
    val distance = 24f * resources.displayMetrics.density
    translationX = if (forward) distance else -distance
    alpha = 0f
    animate().translationX(0f).alpha(1f)
        .setDuration(230)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

package com.cloudin.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.sin

class GlassActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AgslGlassView(context, attrs, defStyleAttr) {

    // Refraction and blur sample beyond the button outline.
    override val backdropPaddingPx: Int get() = dp(20)

    val textView = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(14), 0, dp(14), 0)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.getColor(R.color.shelf_title))
    }

    var text: CharSequence
        get() = textView.text
        set(value) { textView.text = value }

    init {
        useControlOptics(32f)
        isFocusable = true
        val textAttrs = intArrayOf(android.R.attr.text, android.R.attr.textColor, android.R.attr.textSize)
        val values = context.obtainStyledAttributes(attrs, textAttrs)
        textView.text = values.getText(0)
        values.getColorStateList(1)?.let(textView::setTextColor)
        textView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            values.getDimension(2, 14f * resources.displayMetrics.scaledDensity)
        )
        values.recycle()
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal fun GlassActionButton.useBackIcon(color: Int) {
    contentDescription = context.getString(R.string.settings_back)
    textView.visibility = View.GONE
    addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_back)
        setColorFilter(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(context.readerDp(20), context.readerDp(20), Gravity.CENTER))
}

internal fun Context.glassBackButton(color: Int, backdrop: View, tint: Int = Color.WHITE, action: () -> Unit) =
    GlassActionButton(this).apply {
        useBackIcon(color)
        usePopupOptics()
        tintColor = tint
        backdropSource = backdrop
        frosted = GlassStyle.load(context) == GlassStyle.FROSTED
        setGlassClickListener { action() }
    }

/** The click keeps its native hit testing. Multiple actions can rebound one shared glass surface. */
@SuppressLint("ClickableViewAccessibility")
internal fun View.setGlassClickListener(surface: View = this, onClick: (View) -> Unit) {
    val motion = (surface.getTag(R.id.glass_press_motion) as? GlassPressMotion)
        ?: GlassPressMotion(surface).also { surface.setTag(R.id.glass_press_motion, it) }
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> motion.press()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> motion.release(rebound = false)
        }
        false
    }
    setOnClickListener {
        motion.release(rebound = true)
        onClick(it)
    }
}

internal fun View.resetGlassPressMotion() {
    (getTag(R.id.glass_press_motion) as? GlassPressMotion)?.reset()
}

private class GlassPressMotion(private val view: View) {
    private var animation: ValueAnimator? = null

    fun press() = animate(85L, 0.97f, 0f)

    fun release(rebound: Boolean) =
        animate(if (rebound) 350L else 120L, 1f, if (rebound) 1f else 0f)

    fun reset() {
        animation?.cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun animate(milliseconds: Long, target: Float, impulse: Float) {
        animation?.cancel()
        val startX = view.scaleX
        val startY = view.scaleY
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = milliseconds
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedFraction
                val easeOut = 1f - (1f - time) * (1f - time) * (1f - time)
                val wave = sin(2f * Math.PI.toFloat() * time) * (1f - time) * impulse
                view.scaleX = startX + (target - startX) * easeOut + 0.058f * wave
                view.scaleY = startY + (target - startY) * easeOut + 0.072f * wave
            }
            start()
        }
    }
}

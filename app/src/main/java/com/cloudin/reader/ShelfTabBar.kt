package com.cloudin.reader

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.example.liquidglass.LiquidGlassView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** The track, the accessible tab row, and a lens that samples an accent-colored copy of that row. */
class ShelfTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class Tab(val label: String, val icon: Int)

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val surface = LiquidGlassView(context).apply {
        enablePressEffect = false
        enableDynamicBackground = true
        useShaderPipeline = false
        enableChromaticAberration = false
        enableEdgeHighlight = false
        blurAmount = 0.38f
        saturation = 105f
        glassTint = 0xE8FAFAFB.toInt()
        cornerRadius = dp(100).toFloat()
    }
    private val tabs = mutableListOf<LinearLayout>()
    private val accent = context.getColor(R.color.shelf_tab_selected)
    private val ink = context.getColor(R.color.ink)
    private val renderer = TabLensRenderer(context, accent)
    private val selection = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val easeOut = DecelerateInterpolator(1.65f)
    private val rebound = OvershootInterpolator(1.45f)
    private val linear = LinearInterpolator()
    private var motion: ValueAnimator? = null
    private var sourceDirty = true
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private var lensX = 0f
    private var lensY = 0f
    private var lift = 0f
    private var stretch = 1f
    private val hold = Runnable {
        if (pointer != MotionEvent.INVALID_POINTER_ID && isShown) beginDrag()
    }

    internal var glassStyle = GlassStyle.LIQUID
        set(value) {
            field = value
            surface.blurAmount = if (value == GlassStyle.FROSTED) 0.55f else 0.38f
            invalidate()
        }

    var onTabSelected: ((Int) -> Unit)? = null
    var selectedIndex = 0
        set(value) {
            if (value !in tabs.indices || field == value) return
            field = value
            tabs.forEachIndexed { index, tab -> tab.isSelected = index == value }
            settle()
        }

    init {
        // Reserve space inside the view for the lifted lens so HWUI cannot crop its rim.
        clipToPadding = false
        setPadding(dp(32), dp(32), dp(32), dp(16))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
    }

    fun setBackdropSource(view: View) {
        surface.backdropSource = view
    }

    fun setTabs(items: List<Tab>) {
        row.removeAllViews()
        tabs.clear()
        items.forEachIndexed { index, tab ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isSelected = index == selectedIndex
                setOnClickListener { select(index) }
            }
            val icon = ImageView(context).apply {
                setImageResource(tab.icon)
                imageTintList = ColorStateList.valueOf(ink)
            }
            val label = TextView(context).apply {
                text = tab.label
                setTextColor(ink)
                textSize = 11f
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            row.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            tabs.add(item)
        }
        if (selectedIndex !in tabs.indices) selectedIndex = 0
        resetMotion()
        sourceDirty = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val recapture = row.isLayoutRequested || changed
        val oldCenter = centerOf(selectedIndex)
        val oldMiddle = rowCenterY
        val oldWidth = row.width
        super.onLayout(changed, left, top, right, bottom)
        if (oldCenter != centerOf(selectedIndex) || oldMiddle != rowCenterY || oldWidth != row.width) {
            resetMotion()
        }
        sourceDirty = sourceDirty || recapture
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (tabs.isEmpty() || width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }
        if (sourceDirty) {
            renderer.capture(width, height) { snapshot ->
                snapshot.translate(row.left.toFloat(), row.top.toFloat())
                row.draw(snapshot)
            }
            sourceDirty = false
        }
        super.dispatchDraw(canvas)
        val tabWidth = tabs[selectedIndex].width.toFloat()
        val halfWidth = tabWidth * (0.49f + 0.25f * lift) * stretch
        val halfHeight = ((row.height / 2f - dp(4)) + dp(9) * lift) * (1f - (stretch - 1f) * 0.48f)
        selection.set(lensX - halfWidth, lensY - halfHeight, lensX + halfWidth, lensY + halfHeight)
        renderer.draw(canvas, selection, lift, glassStyle == GlassStyle.LIQUID)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (tabs.isEmpty() || !isOnTrack(event.x, event.y)) return false
            pointer = event.getPointerId(0)
            touchX = event.x
            touchY = event.y
            downX = touchX
            downY = touchY
            postDelayed(hold, 160L)
            return true
        }
        if (pointer == MotionEvent.INVALID_POINTER_ID) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            endGesture()
            settle()
            return true
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) return true
        touchX = event.getX(index)
        touchY = event.getY(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(touchX - downX) > touchSlop) beginDrag()
                if (dragging) positionLens()
            }
            MotionEvent.ACTION_UP -> {
                val canSelect = if (dragging) {
                    touchY >= row.top - row.height && touchY <= row.bottom + row.height
                } else {
                    isOnTrack(touchX, touchY)
                }
                if (dragging && canSelect) positionLens()
                endGesture()
                if (canSelect) {
                    val target = tabs.indices.minBy { abs(touchX - centerOf(it)) }
                    tabs[target].performClick()
                } else {
                    settle()
                }
            }
        }
        return true
    }

    private fun select(index: Int) {
        if (selectedIndex == index) {
            settle()
        } else {
            selectedIndex = index
            onTabSelected?.invoke(index)
        }
    }

    private fun beginDrag() {
        removeCallbacks(hold)
        dragging = true
        positionLens()
        val initialLift = lift
        animateMotion(165) { progress -> lift = lerp(initialLift, 1f, progress) }
    }

    private fun positionLens() {
        val first = centerOf(0)
        val last = centerOf(tabs.lastIndex)
        val overhang = tabs[0].width * 0.22f
        lensX = touchX.coerceIn(minOf(first, last) - overhang, maxOf(first, last) + overhang)
        val vertical = ((touchY - downY) / row.height).coerceIn(-1f, 1f)
        lensY = rowCenterY + row.height * 0.20f * vertical
        stretch = 1f - 0.20f * abs(vertical)
        invalidate()
    }

    private fun endGesture() {
        removeCallbacks(hold)
        pointer = MotionEvent.INVALID_POINTER_ID
        dragging = false
    }

    private fun settle() {
        val endX = centerOf(selectedIndex)
        val endY = rowCenterY
        val startX = lensX
        val startY = lensY
        val startLift = lift
        val startStretch = stretch
        val travel = endX - startX
        if (travel == 0f && startY == endY && startLift == 0f && startStretch == 1f) {
            motion?.cancel()
            return
        }

        val lifted = startLift > 0f
        val overshootLimit = dp(7).toFloat()
        val pulse = if (lifted) 0.15f else 0.41f
        animateMotion(if (lifted) 380 else 350, linear) { time ->
            val position = rebound.getInterpolation(time)
            val overshoot = (travel * (position - 1f)).coerceIn(-overshootLimit, overshootLimit)
            lensX = if (position <= 1f) lerp(startX, endX, position) else endX + overshoot
            val settle = easeOut.getInterpolation(time)
            lensY = lerp(startY, endY, settle)
            lift = lerp(startLift, 0f, settle)
            // Stretch in the direction of travel, then briefly compress before settling.
            val oscillation = sin(2f * Math.PI.toFloat() * time) * (1f - time)
            stretch = if (time < 1f) lerp(startStretch, 1f, settle) + pulse * oscillation else 1f
        }
    }

    private fun animateMotion(
        milliseconds: Long,
        timing: TimeInterpolator = easeOut,
        update: (Float) -> Unit
    ) {
        motion?.cancel()
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = milliseconds
            interpolator = timing
            addUpdateListener {
                update(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        resetMotion()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && visibility != VISIBLE) resetMotion()
    }

    private fun resetMotion() {
        endGesture()
        motion?.cancel()
        motion = null
        lift = 0f
        stretch = 1f
        lensX = centerOf(selectedIndex)
        lensY = rowCenterY
    }

    private fun centerOf(index: Int): Float =
        tabs.getOrNull(index)?.let { row.left + it.left + it.width / 2f } ?: width / 2f

    private val rowCenterY: Float get() = row.top + row.height / 2f

    private fun isOnTrack(x: Float, y: Float) =
        x >= surface.left && x < surface.right && y >= surface.top && y < surface.bottom

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun lerp(start: Float, end: Float, amount: Float) = start + (end - start) * amount
}

private class TabLensRenderer(context: Context, accent: Int) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val accentFilter = Paint().apply { colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN) }
    private val shape = Path()
    private var source: BitmapShader? = null
    private val lens = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { OpticalLens(context) }.getOrNull()
    } else null

    fun capture(width: Int, height: Int, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), accentFilter)
        draw(canvas)
        canvas.restoreToCount(layer)
        source = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            if (Build.VERSION.SDK_INT >= 33) lens?.setTexture(it)
        }
    }

    fun draw(canvas: Canvas, bounds: RectF, lift: Float, liquid: Boolean) {
        val texture = source ?: return
        if (Build.VERSION.SDK_INT >= 33 && lift > 0f && liquid && canvas.isHardwareAccelerated && lens != null) {
            lens.draw(canvas, bounds, lift, paint)
            return
        }
        val radius = bounds.height() / 2f
        shape.rewind()
        shape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(shape)
        canvas.drawColor(0xFFEAE7E4.toInt())
        paint.shader = texture
        canvas.drawRect(bounds, paint)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    @RequiresApi(33)
    private class OpticalLens(context: Context) {
        private val density = context.resources.displayMetrics.density
        private val shader = RuntimeShader(
            context.resources.openRawResource(R.raw.shelf_tab_lens).bufferedReader().use { it.readText() }
        )

        fun setTexture(texture: Shader) {
            shader.setInputShader("tabs", texture)
        }

        fun draw(canvas: Canvas, bounds: RectF, lift: Float, paint: Paint) {
            shader.setFloatUniform("center", bounds.centerX(), bounds.centerY())
            shader.setFloatUniform("halfSize", bounds.width() / 2f, bounds.height() / 2f)
            shader.setFloatUniform("bevel", 9f * density)
            shader.setFloatUniform("lift", lift)
            paint.shader = shader
            canvas.drawRect(bounds.left - 1f, bounds.top - 1f, bounds.right + 1f, bounds.bottom + 1f, paint)
            paint.shader = null
        }
    }
}

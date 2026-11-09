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
        glassTint = 0x60FAFAFB
        cornerRadius = dp(100).toFloat()
    }
    private val opticalSurface = AgslGlassView(context).takeIf { it.isShaderSupported }?.apply {
        cornerRadius = dp(28).toFloat()
        refractionHeight = dp(7).toFloat()
        refractionAmount = dp(9).toFloat()
        blurRadius = dp(9).toFloat()
        tintAlpha = 0.24f
    }
    private val tabs = mutableListOf<LinearLayout>()
    private val accent = context.getColor(R.color.shelf_tab_selected)
    private val ink = context.getColor(R.color.ink)
    private val renderer = TabLensRenderer(context, accent)
    private val selection = RectF()
    private val selectionShape = Path()
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
    private var trackPullX = 0f
    private var trackPullY = 0f
    private var trackBounce = 0f
    private var trackTapBounce = 0f
    private val hold = Runnable {
        if (pointer != MotionEvent.INVALID_POINTER_ID && isShown) beginDrag()
    }

    internal var glassStyle = GlassStyle.LIQUID
        set(value) {
            field = value
            surface.blurAmount = if (value == GlassStyle.FROSTED) 0.55f else 0.38f
            updateSurfaceVisibility()
            invalidate()
        }

    var onTabSelected: ((Int) -> Unit)? = null
    private var activeIndex = 0
    var selectedIndex: Int
        get() = activeIndex
        set(value) {
            if (updateSelection(value)) settle()
        }

    private fun updateSelection(index: Int): Boolean {
        if (index !in tabs.indices || index == activeIndex) return false
        activeIndex = index
        tabs.forEachIndexed { position, tab -> tab.isSelected = position == index }
        return true
    }

    init {
        // Reserve space inside the view for the lifted lens so HWUI cannot crop its rim.
        clipToPadding = false
        setPadding(dp(32), dp(32), dp(32), dp(16))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        opticalSurface?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        updateSurfaceVisibility()
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
    }

    fun setBackdropSource(view: View) {
        surface.backdropSource = view
        opticalSurface?.backdropSource = view
    }

    private fun updateSurfaceVisibility() {
        val optical = opticalSurface ?: return
        val liquid = glassStyle == GlassStyle.LIQUID
        optical.visibility = if (liquid) VISIBLE else INVISIBLE
        surface.visibility = if (liquid) INVISIBLE else VISIBLE
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
            selectionShape.rewind()
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
        val tabWidth = tabs[selectedIndex].width.toFloat()
        val trackCenterX = (surface.left + surface.right) / 2f
        val trackCenterY = rowCenterY
        val trackScaleX = 1f + lift * (0.043f + 0.018f * abs(trackPullX)) +
            0.025f * trackBounce + 0.058f * trackTapBounce
        val trackScaleY = 1f + lift * (0.027f + 0.026f * abs(trackPullY)) -
            0.018f * trackBounce + 0.072f * trackTapBounce
        val trackOffsetX = dp(4) * trackPullX * lift
        val trackOffsetY = dp(4) * trackPullY * lift + dp(2) * trackBounce
        // Keep the lens under the finger while the track and its labels move beneath it.
        val followsDrag = dragging || lift > 0f
        val centerX = if (followsDrag) trackCenterX + (lensX - trackCenterX - trackOffsetX) / trackScaleX else lensX
        val centerY = if (followsDrag) trackCenterY + (lensY - trackCenterY - trackOffsetY) / trackScaleY else lensY
        val halfWidth = tabWidth * (0.49f + 0.25f * lift) * stretch
        val halfHeight = ((row.height / 2f - dp(4)) + dp(9) * lift) * (1f - (stretch - 1f) * 0.48f)
        selection.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        selectionShape.rewind()
        val radius = minOf(halfWidth, halfHeight)
        selectionShape.addRoundRect(selection, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.translate(trackOffsetX, trackOffsetY)
        canvas.scale(trackScaleX, trackScaleY, trackCenterX, trackCenterY)
        super.dispatchDraw(canvas)
        renderer.draw(canvas, selection, selectionShape, lift, glassStyle == GlassStyle.LIQUID)
        canvas.restoreToCount(save)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== row || selectionShape.isEmpty) return super.drawChild(canvas, child, drawingTime)
        // The lens draws the selected glyphs in this area. Leaving the normal glyphs would double them.
        val save = canvas.save()
        canvas.clipOutPath(selectionShape)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
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
                if (canSelect) {
                    val target = tabs.indices.minBy { abs(touchX - centerOf(it)) }
                    tabs[target].performClick()
                } else {
                    settle()
                }
                endGesture()
            }
        }
        return true
    }

    private fun select(index: Int) {
        val changed = updateSelection(index)
        settle(tapped = !dragging)
        if (changed) onTabSelected?.invoke(index)
    }

    private fun beginDrag() {
        removeCallbacks(hold)
        dragging = true
        positionLens()
        val initialLift = lift
        val initialBounce = trackBounce
        val initialTapBounce = trackTapBounce
        animateMotion(165) { progress ->
            lift = lerp(initialLift, 1f, progress)
            trackBounce = lerp(initialBounce, 0f, progress)
            trackTapBounce = lerp(initialTapBounce, 0f, progress)
        }
    }

    private fun positionLens() {
        val first = centerOf(0)
        val last = centerOf(tabs.lastIndex)
        val overhang = tabs[0].width * 0.22f
        lensX = touchX.coerceIn(minOf(first, last) - overhang, maxOf(first, last) + overhang)
        val vertical = ((touchY - downY) / row.height).coerceIn(-1f, 1f)
        lensY = rowCenterY + row.height * 0.20f * vertical
        stretch = 1f - 0.20f * abs(vertical)
        trackPullX = ((touchX - downX) / (row.width * 0.65f)).coerceIn(-1f, 1f)
        trackPullY = vertical
        invalidate()
    }

    private fun endGesture() {
        removeCallbacks(hold)
        pointer = MotionEvent.INVALID_POINTER_ID
        dragging = false
    }

    private fun settle(tapped: Boolean = false) {
        val endX = centerOf(selectedIndex)
        val endY = rowCenterY
        val startX = lensX
        val startY = lensY
        val startLift = lift
        val startStretch = stretch
        val startPullX = trackPullX
        val startPullY = trackPullY
        val startBounce = trackBounce
        val startTapBounce = trackTapBounce
        val travel = endX - startX
        val trackIsResting = startPullX == 0f && startPullY == 0f && startBounce == 0f && startTapBounce == 0f
        if (!tapped && travel == 0f && startY == endY && startLift == 0f && startStretch == 1f && trackIsResting) {
            resetMotion()
            return
        }

        val lifted = startLift > 0f
        val overshootLimit = dp(7).toFloat()
        val pulse = if (lifted) 0.15f else if (travel != 0f) 0.41f else 0f
        val trackImpulse = if (lifted && !tapped) 1.25f else 0f
        val tapImpulse = if (tapped) 1f else 0f
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
            trackPullX = lerp(startPullX, 0f, settle)
            trackPullY = lerp(startPullY, 0f, settle)
            trackBounce = lerp(startBounce, 0f, settle) + trackImpulse * oscillation
            trackTapBounce = lerp(startTapBounce, 0f, settle) + tapImpulse * oscillation
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
        trackPullX = 0f
        trackPullY = 0f
        trackBounce = 0f
        trackTapBounce = 0f
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

    fun draw(canvas: Canvas, bounds: RectF, shape: Path, lift: Float, liquid: Boolean) {
        val texture = source ?: return
        if (Build.VERSION.SDK_INT >= 33 && lift > 0f && liquid && canvas.isHardwareAccelerated && lens != null) {
            lens.draw(canvas, bounds, lift, paint)
            return
        }
        val save = canvas.save()
        canvas.clipPath(shape)
        canvas.drawColor(if (lift > 0f) 0x49FFFFFF else 0x202B241C)
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

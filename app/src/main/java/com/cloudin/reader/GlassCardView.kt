package com.cloudin.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout

/**
 * 玻璃卡片：用 BitmapShader 按窗口坐标取样背后的模糊画面。
 *
 * 与"抓一块面板大小的图"不同，这里抓的是整页，再用 shader 的 localMatrix
 * 把卡片在窗口中的位置映射进去，所以卡片高度怎么变都不会出现"下半截没糊"。
 */
class GlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var shader: BitmapShader? = null
    private var srcX = 0
    private var srcY = 0
    private var source: View? = null
    private var dirty = true
    private var lastRefresh = 0L
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var tintAlpha = 0.18f
    private var blurRadius = 3
    private var radiusPx = 26f * resources.displayMetrics.density
    private val scale = 0.28f

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.25f) })
    }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                source = null
            }
        })
        clipChildren = false
        clipToPadding = false
        elevation = 0f
    }

    fun setCornerRadius(px: Float) {
        radiusPx = px
        invalidate()
    }

    fun setTintStrength(alpha: Float) {
        tintAlpha = alpha.coerceIn(0.05f, 0.95f)
        invalidate()
    }

    fun setBlurRadius(r: Int) {
        blurRadius = r.coerceIn(1, 24)
    }

    fun requestRefresh() {
        dirty = true
        invalidate()
    }

    fun captureFrom(view: View) {
        source = view
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val bw = (view.width * scale).toInt().coerceAtLeast(1)
            val bh = (view.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            view.draw(c)
            val blurred = Blur.blurred(bmp, radius = blurRadius, scale = 1f)

            val vl = IntArray(2)
            view.getLocationInWindow(vl)
            srcX = vl[0]
            srcY = vl[1]
            shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            invalidate()
        }
    }

    private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
        val src = source
        val now = System.currentTimeMillis()
        if (src != null && isShown) {
            val loc = IntArray(2)
            getLocationInWindow(loc)
            if (dirty && now - lastRefresh > 120) {
                dirty = false
                lastRefresh = now
                captureFrom(src)
            }
        }
        true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rect = RectF(0f, 0f, w, h)
        val path = Path().apply { addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(path)

        shader?.let { s ->
            val ml = IntArray(2)
            getLocationInWindow(ml)
            val m = Matrix()
            m.setScale(1f / scale, 1f / scale)
            m.postTranslate(-(ml[0] - srcX).toFloat(), -(ml[1] - srcY).toFloat())
            s.setLocalMatrix(m)
            val liquid = LiquidGlass.create()
            if (liquid != null) {
                liquid.setInputShader("content", s)
                LiquidGlass.configure(liquid, w, h, dp(26f), 0.55f)
                backdropPaint.shader = liquid
            } else {
                backdropPaint.shader = s
            }
            canvas.drawRect(rect, backdropPaint)
        }

        val a = (255 * tintAlpha).toInt()
        washPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                (a shl 24) or 0xFFFFFF,
                (((a * 0.7f).toInt()) shl 24) or 0xFFFFFF,
                (((a * 0.85f).toInt()) shl 24) or 0xF3F7FF
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, washPaint)

        specPaint.shader = RadialGradient(
            w * 0.3f, -h * 0.1f, h * 1.15f,
            intArrayOf(0x66FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, specPaint)
        canvas.restore()

        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xE6FFFFFF.toInt(), 0x40FFFFFF, 0x33000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            RectF(0.6f, 0.6f, w - 0.6f, h - 0.6f),
            radiusPx, radiusPx, rimPaint
        )

        super.dispatchDraw(canvas)
    }
}

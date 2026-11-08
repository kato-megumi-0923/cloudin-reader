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
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 玻璃胶囊 / 玻璃栏。
 *
 * 与 GlassCardView 同一套取样方式：整页抓一次 → 模糊 → 用 BitmapShader
 * 按"本栏在窗口中的位置"取样。任意尺寸、任意位置都不用重新抓图。
 */
class GlassBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var shader: BitmapShader? = null
    private var srcX = 0
    private var srcY = 0
    private var source: View? = null
    private var dirty = true
    private var lastRefresh = 0L

    private var tintAlpha = 0.18f
    private var blurRadius = 3
    private val scale = 0.28f
    private val radius = 30f * resources.displayMetrics.density

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.25f) })
    }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        elevation = 0f
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                source = null
            }
        })
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

    fun clearBackdrop() {
        shader = null
        invalidate()
    }

    fun captureFrom(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        source = view
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
            lastRefresh = System.currentTimeMillis()
            invalidate()
        }
    }

    private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
        val src = source
        val now = System.currentTimeMillis()
        if (dirty && src != null && isShown && now - lastRefresh > 120) {
            dirty = false
            captureFrom(src)
        }
        true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rect = RectF(0f, 0f, w, h)
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

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
                LiquidGlass.configure(liquid, w, h, dp(22f), 0.5f)
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
            RectF(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f)),
            radius, radius, rimPaint
        )

        super.dispatchDraw(canvas)
    }
}

package com.cloudin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * 液态玻璃容器：把背景 View 录进 RenderNode，用 AGSL（RuntimeShader）做圆角 SDF 折射 +
 * 色散 + 边缘高光，然后把 node 画在自己下面再画子 View。
 *
 * 着色器来自 AndroidLiquidGlassView（MIT，QmDeve），算法参考 IT之家 的改版。
 * Android 13 以下退化为半透明圆角色块。
 */
open class AgslGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var cornerRadius: Float = dp(24f)
        set(value) { field = value; needsUpdate = true; invalidate() }

    var refractionHeight: Float = dp(18f)
        set(value) { field = value; needsUpdate = true; invalidate() }

    var refractionAmount: Float = dp(26f)
        set(value) { field = value; needsUpdate = true; invalidate() }

    var dispersion: Float = 0.14f
        set(value) { field = value; needsUpdate = true; invalidate() }

    private var edgeHighlight: Float = 1.1f
        set(value) { field = value; needsUpdate = true; invalidate() }

    var blurRadius: Float = dp(6f)
        set(value) { field = value; blurEffect = null; needsUpdate = true; invalidate() }

    internal var frosted = false
        set(value) {
            if (field == value) return
            field = value
            blurEffect = null
            needsUpdate = true
            invalidate()
        }

    var tintColor: Int = Color.WHITE
        set(value) { field = value; needsUpdate = true; invalidate() }

    var tintAlpha: Float = 0.10f
        set(value) { field = value; needsUpdate = true; invalidate() }

    internal fun useControlOptics(radiusDp: Float) {
        cornerRadius = dp(radiusDp)
        refractionHeight = dp(7f)
        refractionAmount = dp(9f)
        blurRadius = dp(9f)
        tintAlpha = 0.24f
    }

    internal fun usePopupOptics() {
        refractionHeight = dp(3f)
        refractionAmount = dp(2f)
        edgeHighlight = 0f
    }

    /** 背景内容源；不设置时用父容器。 */
    var backdropSource: View? = null
        set(value) { field = value; needsUpdate = true; invalidate() }

    protected open val backdropPaddingPx: Int get() = 0

    private val path = Path()
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var needsUpdate = true

    private var node: RenderNode? = null
    private var shader: RuntimeShader? = null
    private var blurEffect: RenderEffect? = null
    private var effectAttached = false
    private var recording = false
    private var supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    internal val isShaderSupported: Boolean get() = supported

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (supported && isShown) {
            record()
            if (needsUpdate || !effectAttached) applyEffect()
        }
        true
    }

    init {
        setWillNotDraw(false)
        if (supported) {
            supported = runCatching {
                node = RenderNode("AgslGlassView")
                shader = RuntimeShader(readShader(context))
                true
            }.getOrDefault(false)
        }
        fallbackPaint.color = Color.argb(36, 255, 255, 255)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        runCatching { viewTreeObserver.removeOnPreDrawListener(preDraw) }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = backdropPaddingPx
        node?.setPosition(-padding, -padding, w + padding, h + padding)
        needsUpdate = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val n = node
        if (supported && canvas.isHardwareAccelerated && n != null) {
            canvas.drawRenderNode(n)
        } else {
            drawFallback(canvas)
        }
        super.dispatchDraw(canvas)
    }

    private fun drawFallback(canvas: Canvas) {
        path.rewind()
        path.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
        fallbackPaint.color = (if (frosted) 0x76 else 0x50) shl 24 or (tintColor and 0xFFFFFF)
        canvas.drawPath(path, fallbackPaint)
    }

    private fun record() {
        // 录制目标里可能包含自己（比如底栏透镜在 root 里），避免递归
        if (recording) return
        val target = backdropSource ?: (parent as? View) ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || target.width <= 0 || target.height <= 0) return
        val n = node ?: return
        val padding = backdropPaddingPx
        recording = true
        try {
            val canvas = n.beginRecording(w + padding * 2, h + padding * 2)
            // 对话框是独立 window，必须用屏幕坐标对齐，否则采到的是错位的区域（会露出黑块）
            getLocationOnScreen(location)
            target.getLocationOnScreen(sourceLocation)
            canvas.translate(
                (padding - location[0] + sourceLocation[0]).toFloat(),
                (padding - location[1] + sourceLocation[1]).toFloat()
            )
            target.draw(canvas)
            n.endRecording()
        } finally {
            recording = false
        }
    }

    private fun applyEffect() {
        val s = shader ?: return
        val n = node ?: return
        runCatching {
            s.setFloatUniform("size", floatArrayOf(width.toFloat(), height.toFloat()))
            val captureOffset = -backdropPaddingPx.toFloat()
            s.setFloatUniform("offset", floatArrayOf(captureOffset, captureOffset))
            s.setFloatUniform("u_time", 0f)
            val corner = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
            s.setFloatUniform(
                "cornerRadius",
                floatArrayOf(corner, corner, corner, corner)
            )
            s.setFloatUniform("refractionHeight", if (frosted) 0f else refractionHeight)
            s.setFloatUniform("refractionAmount", if (frosted) 0f else refractionAmount)
            s.setFloatUniform("refractionAxis", floatArrayOf(1f, 0.72f))
            s.setFloatUniform("isDark", if (androidx.core.graphics.ColorUtils.calculateLuminance(tintColor) < 0.22) 1f else 0f)
            s.setFloatUniform("lr", 0.5f)
            s.setFloatUniform("ld", 0.10f)
            s.setFloatUniform("dr", 0.55f)
            s.setFloatUniform("dd", 0.06f)
            s.setFloatUniform("edgeHighlight", edgeHighlight)
            s.setFloatUniform("depthEffect", 0.35f)
            s.setFloatUniform("dispersion", dispersion)
            s.setFloatUniform("edgeDispersion", 1f)
            s.setFloatUniform("contrast", 0.02f)
            s.setFloatUniform("whitePoint", 0.03f)
            s.setFloatUniform("chromaMultiplier", 1.05f)
            s.setFloatUniform(
                "tintColor",
                floatArrayOf(
                    Color.red(tintColor) / 255f,
                    Color.green(tintColor) / 255f,
                    Color.blue(tintColor) / 255f
                )
            )
            s.setFloatUniform("tintAlpha", tintAlpha)

            val shaderEffect = RenderEffect.createRuntimeShaderEffect(s, "rmContent")
            val radius = if (frosted) maxOf(blurRadius, dp(13f)) else blurRadius
            val blur = if (radius > 0.01f) {
                blurEffect ?: RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP
                ).also { blurEffect = it }
            } else null
            n.setRenderEffect(if (blur != null) RenderEffect.createChainEffect(shaderEffect, blur) else shaderEffect)
            effectAttached = true
            needsUpdate = false
        }
    }

    private fun readShader(context: Context): String =
        context.resources.openRawResource(R.raw.agsl_glass).bufferedReader().use { it.readText() }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

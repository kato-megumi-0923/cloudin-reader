package com.cloudin.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 书架「喜爱」按钮的点击动效。
 *
 * 做法是在 decorView 顶部临时铺一层不拦截触摸的绘制层，动画结束就移除 ——
 * 不去改 item_book / item_book_list 的布局，网格与列表两种排布都能直接用。
 *
 * 绘制全部按「时间 → 位置」的解析公式算，不在帧间累加速度，掉帧时轨迹不会变形。
 */
internal object HeartFx {

    private const val TAG_FX = "cloudin_heart_fx"

    private val RED = 0xFFFF4D5E.toInt()
    private val PINK = 0xFFFF8A98.toInt()
    private val LIGHT = 0xFFFFC9D0.toInt()
    private val GOLD = 0xFFFFD166.toInt()
    private val ASH = 0xFF9A9AA2.toInt()
    private val ASH_DIM = 0xFF74747C.toInt()

    private const val PI_F = 3.1415927f

    /** 与图标同源的心形轮廓，50×46 为中心。 */
    private val heartPath: Path by lazy {
        PathParser.createPathFromPathData(
            "M50 88C50 88 6 58 6 32C6 12 22 4 34 4C42 4 48 8 50 14C52 8 58 4 66 4C78 4 94 12 94 32C94 58 50 88 50 88Z"
        )
    }

    /**
     * 播放一次动效。
     *
     * @param anchor 被点击的心形图标，用来定位与回弹
     * @param liked 点击**之后**是否已收藏
     */
    fun play(activity: Activity?, anchor: View, anim: LikeAnim, liked: Boolean) {
        play(activity?.window?.decorView as? ViewGroup, anchor, anim, liked)
    }

    /**
     * 指定绘制层所在的容器。弹窗里预览时要传弹窗自己的根视图 ——
     * 铺在 Activity 上的话会被弹窗完全遮住。
     */
    fun play(root: ViewGroup?, anchor: View, anim: LikeAnim, liked: Boolean) {
        pop(anchor)
        if (anim == LikeAnim.LOVE) return
        val decor = root ?: return

        // 连点时只换掉同一颗心上的旧层：一行里给「旧选中的心」播取消、
        // 「新选中的心」播喜欢，两层要能同时在。
        val key = TAG_FX + System.identityHashCode(anchor)
        for (i in decor.childCount - 1 downTo 0) {
            if (decor.getChildAt(i).tag == key) decor.removeViewAt(i)
        }

        val anchorPos = IntArray(2)
        anchor.getLocationOnScreen(anchorPos)
        val decorPos = IntArray(2)
        decor.getLocationOnScreen(decorPos)

        val layer = FxLayer(decor.context).apply {
            variant = anim
            this.liked = liked
            cx = (anchorPos[0] - decorPos[0] + anchor.width / 2f)
            cy = (anchorPos[1] - decorPos[1] + anchor.height / 2f)
            size = minOf(anchor.width, anchor.height).toFloat().coerceAtLeast(1f)
            tag = key
            isClickable = false
            isFocusable = false
        }
        // 弹窗的根视图是 wrap_content，塞一个 match_parent 的子视图会把弹窗撑成整屏，
        // 所以按它当前的实际尺寸铺一层
        val width = decor.width.takeIf { it > 0 } ?: decor.measuredWidth
        val height = decor.height.takeIf { it > 0 } ?: decor.measuredHeight
        val params = if (width > 0 && height > 0) ViewGroup.LayoutParams(width, height)
        else ViewGroup.LayoutParams(-1, -1)

        decor.addView(layer, params)
        layer.start()

        val life = layer.duration + 60L
        layer.postDelayed({ (layer.parent as? ViewGroup)?.removeView(layer) }, life)
    }

    fun activityOf(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private val popping = java.util.WeakHashMap<View, ValueAnimator>()

    /** 0.86 → 1.17 → 0.97 → 1，340ms，和原型里的一致。 */
    private fun pop(anchor: View) {
        // 连点时先掐掉上一次，否则两个动画抢着改 scale
        popping.remove(anchor)?.cancel()
        val va = ValueAnimator.ofFloat(0f, 1f).setDuration(340L)
        va.interpolator = LinearInterpolator()
        va.addUpdateListener { value ->
            val p = value.animatedValue as Float
            val s = when {
                p < 0.38f -> 0.86f + 0.31f * easeOut(p / 0.38f)
                p < 0.66f -> 1.17f - 0.20f * easeOut((p - 0.38f) / 0.28f)
                else -> 0.97f + 0.03f * easeOut((p - 0.66f) / 0.34f)
            }
            anchor.scaleX = s
            anchor.scaleY = s
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                anchor.scaleX = 1f
                anchor.scaleY = 1f
                if (popping[anchor] === animation) popping.remove(anchor)
            }
        })
        popping[anchor] = va
        va.start()
    }

    private fun easeOut(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return 1f - (1f - v) * (1f - v) * (1f - v)
    }

    private fun easeOutQuint(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        val r = 1f - v
        return 1f - r * r * r * r * r
    }

    private class Ring(
        val r0: Float, val r1: Float, val start: Float,
        val dur: Float, val color: Int, val width: Float
    )

    private class Mote(
        val bx: Float, val by: Float, val vx: Float, val vy: Float,
        val s: Float, val rot: Float, val spin: Float, val color: Int,
        val delay: Float, val heart: Boolean, val life: Float, val gravity: Float
    )

    private class Spark(val angle: Float, val len: Float, val width: Float, val color: Int)

    private class Shard(
        val ox: Float, val oy: Float, val vx: Float, val vy: Float,
        val w: Float, val h: Float, val rot: Float, val spin: Float, val delay: Float
    )

    private class FxLayer(context: Context) : View(context) {

        var variant: LikeAnim = LikeAnim.BEAT
        var liked = true
        var cx = 0f
        var cy = 0f
        var size = 24f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rings = ArrayList<Ring>(3)
        private val motes = ArrayList<Mote>(20)
        private val sparks = ArrayList<Spark>(10)
        private val shards = ArrayList<Shard>(13)

        /** 取消收藏时叠一个正在缩掉的红心，露出底下已经变灰的图标。 */
        private var flashHeart = false
        /** 线性量都按 size / 46 缩放，24dp 和 48dp 的观感一致。 */
        private var k = 1f
        var duration = 0L
            private set

        private var startedAt = 0L

        fun start() {
            k = size / 46f
            build()
            startedAt = SystemClock.uptimeMillis()
            invalidate()
        }

        private fun build() {
            rings.clear()
            motes.clear()
            sparks.clear()
            shards.clear()
            flashHeart = false

            when (variant) {
                LikeAnim.LOVE -> duration = 340L

                LikeAnim.BEAT -> if (liked) {
                    duration = 820L
                    rings += Ring(0.42f, 1.55f, 0f, 430f, RED, 2.4f * k)
                    rings += Ring(0.30f, 1.15f, 70f, 380f, PINK, 1.6f * k)
                    spawnMotes(
                        16, vMin = 80f, vMax = 205f, sMin = 0.055f, sMax = 0.13f,
                        spread = 1.85f, from = 0.3f, jitter = 90f, life = 640f,
                        gravity = 235f, heartRatio = 0.42f,
                        colors = intArrayOf(RED, PINK, LIGHT, GOLD)
                    )
                } else {
                    duration = 620L
                    flashHeart = true
                    rings += Ring(1.15f, 0.42f, 0f, 260f, ASH, 1.6f * k)
                    spawnMotes(
                        7, vMin = 20f, vMax = 62f, sMin = 0.04f, sMax = 0.07f,
                        spread = 2.6f, from = 0.2f, jitter = 60f, life = 560f,
                        gravity = 420f, heartRatio = 0f,
                        colors = intArrayOf(ASH, ASH_DIM)
                    )
                }

                LikeAnim.SHATTER -> if (liked) {
                    duration = 900L
                    rings += Ring(0.40f, 1.90f, 0f, 520f, RED, 3f * k)
                    rings += Ring(0.28f, 1.35f, 90f, 420f, PINK, 2f * k)
                    rings += Ring(0.20f, 0.95f, 180f, 350f, LIGHT, 1.4f * k)
                    for (i in 0 until 10) {
                        val a = (i / 10f) * (PI_F * 2f) + Random.nextFloat() * 0.3f
                        sparks += Spark(
                            a, (0.65f + Random.nextFloat() * 0.55f) * size,
                            (1f + Random.nextFloat() * 1.1f) * k,
                            if (i % 3 == 0) GOLD else PINK
                        )
                    }
                    spawnMotes(
                        20, vMin = 110f, vMax = 265f, sMin = 0.045f, sMax = 0.11f,
                        spread = 2.1f, from = 0.35f, jitter = 60f, life = 700f,
                        gravity = 300f, heartRatio = 0.3f,
                        colors = intArrayOf(RED, PINK, LIGHT, GOLD, 0xFFFFFFFF.toInt())
                    )
                } else {
                    duration = 900L
                    flashHeart = true
                    for (i in 0 until 13) {
                        val a = Random.nextFloat() * (PI_F * 2f)
                        val r = Random.nextFloat() * size * 0.34f
                        shards += Shard(
                            ox = cos(a) * r,
                            oy = sin(a) * r,
                            vx = cos(a) * (42f + Random.nextFloat() * 72f) * k,
                            vy = (-50f - Random.nextFloat() * 55f) * k,
                            w = (0.074f + Random.nextFloat() * 0.117f) * size,
                            h = (0.061f + Random.nextFloat() * 0.1f) * size,
                            rot = Random.nextFloat() * 6.28f,
                            spin = (Random.nextFloat() - 0.5f) * 7f,
                            delay = Random.nextFloat() * 70f
                        )
                    }
                }
            }
        }

        private fun spawnMotes(
            count: Int, vMin: Float, vMax: Float, sMin: Float, sMax: Float,
            spread: Float, from: Float, jitter: Float, life: Float,
            gravity: Float, heartRatio: Float, colors: IntArray
        ) {
            for (i in 0 until count) {
                // 主要向上散开，少量偏两侧
                val a = -PI_F / 2 + (Random.nextFloat() - 0.5f) * PI_F * spread
                val speed = (vMin + Random.nextFloat() * (vMax - vMin)) * k
                val s = (sMin + Random.nextFloat() * (sMax - sMin)) * size
                motes += Mote(
                    bx = cx + cos(a) * size * from,
                    by = cy + sin(a) * size * from,
                    vx = cos(a) * speed,
                    vy = sin(a) * speed,
                    s = s,
                    rot = Random.nextFloat() * 6.28f,
                    spin = (Random.nextFloat() - 0.5f) * 6f,
                    color = colors[i % colors.size],
                    delay = Random.nextFloat() * jitter,
                    heart = Random.nextFloat() < heartRatio,
                    life = life,
                    gravity = gravity * k
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (startedAt == 0L) return
            val t = (SystemClock.uptimeMillis() - startedAt).toFloat()

            if (flashHeart) drawFlashHeart(canvas, t)

            for (i in rings.indices) drawRing(canvas, rings[i], t)
            for (i in sparks.indices) drawSpark(canvas, sparks[i], t)
            for (i in motes.indices) drawMote(canvas, motes[i], t)
            for (i in shards.indices) drawShard(canvas, shards[i], t)

            if (t < duration) postInvalidateOnAnimation()
        }

        private fun drawHeart(
            canvas: Canvas, x: Float, y: Float, s: Float,
            color: Int, alpha: Int, rot: Float
        ) {
            if (alpha <= 0 || s < 0.6f) return
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(rot)
            val scale = s / 100f
            canvas.scale(scale, scale)
            canvas.translate(-50f, -46f)
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = alpha
            canvas.drawPath(heartPath, paint)
            canvas.restore()
        }

        private fun drawFlashHeart(canvas: Canvas, t: Float) {
            // 心动取消：单纯缩掉；心碎取消：先闪一下再缩掉
            if (variant == LikeAnim.SHATTER) {
                val scale = when {
                    t < 90f -> 1f + 0.06f * easeOut(t / 90f)
                    t < 220f -> 1.06f - 0.44f * ((t - 90f) / 130f)
                    else -> 0.62f * (1f - ((t - 220f) / 100f).coerceIn(0f, 1f))
                }
                val fade = 1f - ((t - 150f) / 170f).coerceIn(0f, 1f)
                drawHeart(canvas, cx, cy, size * scale, RED, (fade * 255f).toInt(), 0f)
            } else {
                val scale = when {
                    t < 130f -> 1f - 0.45f * easeOut(t / 130f)
                    else -> 0.55f * (1f - ((t - 130f) / 70f).coerceIn(0f, 1f))
                }
                val fade = 1f - (t / 190f).coerceIn(0f, 1f)
                drawHeart(canvas, cx, cy, size * scale, RED, (fade * 255f).toInt(), 0f)
            }
        }

        private fun drawRing(canvas: Canvas, ring: Ring, t: Float) {
            val p = ((t - ring.start) / ring.dur).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) return
            val e = easeOutQuint(p)
            val r = (ring.r0 + (ring.r1 - ring.r0) * e) * size
            paint.style = Paint.Style.STROKE
            paint.color = ring.color
            paint.alpha = ((1f - e) * (1f - e) * 255f).toInt().coerceIn(0, 255)
            paint.strokeWidth = (ring.width * (1f - e)).coerceAtLeast(0.6f)
            canvas.drawCircle(cx, cy, r, paint)
        }

        private fun drawSpark(canvas: Canvas, spark: Spark, t: Float) {
            val p = (t / 380f).coerceIn(0f, 1f)
            if (p >= 1f) return
            val e = easeOutQuint(p)
            val r0 = size * 0.42f
            val r1 = r0 + spark.len * e
            val dx = cos(spark.angle)
            val dy = sin(spark.angle) * 0.92f
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = spark.color
            paint.alpha = ((1f - e) * 217f).toInt().coerceIn(0, 255)
            paint.strokeWidth = (spark.width * (1f - e * 0.6f)).coerceAtLeast(0.6f)
            canvas.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }

        private fun drawMote(canvas: Canvas, mote: Mote, t: Float) {
            val tt = t - mote.delay
            if (tt <= 0f) return
            val p = (tt / mote.life).coerceIn(0f, 1f)
            if (p >= 1f) return
            val sec = tt / 1000f
            val x = mote.bx + mote.vx * sec
            val y = mote.by + mote.vy * sec + 0.5f * mote.gravity * sec * sec
            val alpha = ((1f - p * p) * 255f).toInt().coerceIn(0, 255)
            if (mote.heart) {
                drawHeart(
                    canvas, x, y, mote.s * 2.6f * (1f - 0.25f * p),
                    mote.color, alpha, mote.rot + mote.spin * sec
                )
            } else {
                paint.style = Paint.Style.FILL
                paint.color = mote.color
                paint.alpha = alpha
                canvas.drawCircle(x, y, mote.s * 0.55f * (1f - 0.3f * p), paint)
            }
        }

        private fun drawShard(canvas: Canvas, shard: Shard, t: Float) {
            val tt = t - shard.delay
            if (tt <= 0f) return
            val p = (tt / 760f).coerceIn(0f, 1f)
            if (p >= 1f) return
            val sec = tt / 1000f
            val x = cx + shard.ox + shard.vx * sec
            val y = cy + shard.oy + shard.vy * sec + 0.5f * 520f * k * sec * sec
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((shard.rot + shard.spin * sec) * 57.29578f)
            paint.style = Paint.Style.FILL
            paint.color = if (p < 0.45f) RED else PINK
            paint.alpha = ((1f - p) * 230f).toInt().coerceIn(0, 255)
            canvas.drawRect(-shard.w / 2f, -shard.h / 2f, shard.w / 2f, shard.h / 2f, paint)
            canvas.restore()
        }

    }
}

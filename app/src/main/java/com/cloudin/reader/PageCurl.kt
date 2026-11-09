package com.cloudin.reader

import android.graphics.Matrix
import android.graphics.Path
import kotlin.math.hypot

/** Folds the page between a fixed corner (or side grip) and the live finger position. */
internal class PageCurl {
    val exposed = Path()
    val paperBack = Path()
    val reflection = Matrix()
    var creaseX = 0f
        private set
    var creaseY = 0f
        private set
    var normalX = 0f
        private set
    var normalY = 0f
        private set

    private val rectangle = FloatArray(8)
    private val polygon = FloatArray(12)
    private val reflected = FloatArray(12)
    private val onCrease = BooleanArray(6)
    private val matrixValues = FloatArray(9)

    fun update(width: Float, height: Float, anchorX: Float, anchorY: Float, tipX: Float, tipY: Float) {
        creaseX = (anchorX + tipX) / 2f
        creaseY = (anchorY + tipY) / 2f
        val dx = anchorX - tipX
        val dy = anchorY - tipY
        val length = hypot(dx, dy)
        exposed.rewind()
        paperBack.rewind()
        if (length < 0.001f) return
        normalX = dx / length
        normalY = dy / length

        rectangle[2] = width
        rectangle[4] = width
        rectangle[5] = height
        rectangle[7] = height
        var count = 0
        var x = rectangle[6]
        var y = rectangle[7]
        var distance = signedDistance(x, y)
        for (index in rectangle.indices step 2) {
            val nextX = rectangle[index]
            val nextY = rectangle[index + 1]
            val nextDistance = signedDistance(nextX, nextY)
            if ((distance >= 0f) != (nextDistance >= 0f)) {
                val fraction = distance / (distance - nextDistance)
                polygon[count * 2] = x + (nextX - x) * fraction
                polygon[count * 2 + 1] = y + (nextY - y) * fraction
                onCrease[count++] = true
            }
            if (nextDistance >= 0f) {
                polygon[count * 2] = nextX
                polygon[count * 2 + 1] = nextY
                onCrease[count++] = nextDistance == 0f
            }
            x = nextX
            y = nextY
            distance = nextDistance
        }
        if (count < 3) return

        val offset = creaseX * normalX + creaseY * normalY
        val cross = -2f * normalX * normalY
        matrixValues[0] = 1f - 2f * normalX * normalX
        matrixValues[1] = cross
        matrixValues[2] = 2f * offset * normalX
        matrixValues[3] = cross
        matrixValues[4] = 1f - 2f * normalY * normalY
        matrixValues[5] = 2f * offset * normalY
        matrixValues[8] = 1f
        reflection.setValues(matrixValues)
        reflection.mapPoints(reflected, 0, polygon, 0, count)
        val bend = minOf(length * 0.10f, (2f * width - kotlin.math.abs(dx)).coerceAtLeast(0f) * 0.08f, width * 0.045f)
        trace(exposed, polygon, count, bend)
        trace(paperBack, reflected, count, bend)
    }

    private fun trace(path: Path, points: FloatArray, count: Int, bend: Float) {
        path.moveTo(points[0], points[1])
        for (i in 0 until count) {
            val next = (i + 1) % count
            val endX = points[next * 2]
            val endY = points[next * 2 + 1]
            if (onCrease[i] && onCrease[next]) {
                val startX = points[i * 2]
                val startY = points[i * 2 + 1]
                val curve = minOf(bend, hypot(endX - startX, endY - startY) * 0.12f)
                path.quadTo((startX + endX) / 2f + normalX * curve, (startY + endY) / 2f + normalY * curve, endX, endY)
            } else {
                path.lineTo(endX, endY)
            }
        }
        path.close()
    }

    private fun signedDistance(x: Float, y: Float): Float =
        (x - creaseX) * normalX + (y - creaseY) * normalY
}

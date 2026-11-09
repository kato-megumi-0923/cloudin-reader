package com.cloudin.reader

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * iOS 26「液态玻璃」的边缘折射。
 *
 * 取自 ShaderToy 的 LiquidGlass 思路：靠近边缘时，采样坐标被沿法线方向推开一点，
 * 相当于透过透镜看背景，产生边缘弯曲 + 高光聚集。
 *
 * BitmapShader 负责"模糊背景"，这个 shader 负责"扭曲采样"。
 */
object LiquidGlass {

    private const val SHADER = """
        uniform shader content;
        uniform float2 uSize;
        uniform float uEdge;
        uniform float uRefract;

        half4 main(float2 p) {
            float2 c = uSize * 0.5;
            float2 d = p - c;
            float halfMin = min(uSize.x, uSize.y) * 0.5;
            float r = length(d) / max(halfMin, 1.0);
            // 越靠近边缘，折射越强
            float e = smoothstep(0.55, 1.05, r);
            float2 dir = d / max(length(d), 0.001);
            float2 q = p - dir * e * uEdge * uRefract;
            return content.eval(q);
        }
    """

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 33

    fun create(): RuntimeShader? {
        if (!isSupported()) return null
        return runCatching { RuntimeShader(SHADER) }.getOrNull()
    }

    @RequiresApi(33)
    fun configure(shader: RuntimeShader, width: Float, height: Float, edge: Float, refract: Float) {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uEdge", edge)
        shader.setFloatUniform("uRefract", refract)
    }
}

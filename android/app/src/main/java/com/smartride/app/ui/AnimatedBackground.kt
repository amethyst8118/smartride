package com.smartride.app.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen animated backdrop, generated on the GPU (AGSL) from the theme's
 * colours, so it is sharp at any resolution and needs no media assets.
 * Styles mirror the motion of the original app's backdrops: glossy silk waves,
 * ink/aurora, soft colour flow and diagonal ribbons.
 */
enum class BackdropStyle { SILK, INK, FLOW, RIBBONS }

@Composable
fun AnimatedBackground(th: AppTheme, style: BackdropStyle, animate: Boolean, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }

    val palette = remember(th) { Palette.of(th) }
    val shader = if (Build.VERSION.SDK_INT >= 33) rememberShader(style) else null

    Box(modifier.fillMaxSize().background(palette.base)) {
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    shader.setFloatUniform("iResolution", size.width, size.height)
                    shader.setFloatUniform("iTime", time.floatValue)
                    shader.setFloatUniform("cBase", palette.base.red, palette.base.green, palette.base.blue)
                    shader.setFloatUniform("cA", palette.a.red, palette.a.green, palette.a.blue)
                    shader.setFloatUniform("cB", palette.b.red, palette.b.green, palette.b.blue)
                    drawRect(ShaderBrush(shader))
                },
            )
        } else {
            FallbackBlobs(palette) { time.floatValue }
        }
        // Readability scrim, as in the original: the glass panels sit on top of this.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        th.bgStart.toColor().copy(alpha = if (th.isLight) 0.30f else 0.45f),
                        th.bgEnd.toColor().copy(alpha = if (th.isLight) 0.55f else 0.70f),
                    ),
                ),
            ),
        )
    }
}

private data class Palette(val base: Color, val a: Color, val b: Color) {
    companion object {
        fun of(th: AppTheme): Palette {
            val accent = th.accent.toColor()
            val soft = th.accentSoft.toColor()
            return if (th.isLight) {
                Palette(base = th.bgStart.toColor(), a = lerp(accent, Color.White, 0.15f), b = Color.White)
            } else {
                Palette(base = th.bgEnd.toColor(), a = lerp(accent, Color.Black, 0.25f), b = soft)
            }
        }
    }
}

@RequiresApi(33)
@Composable
private fun rememberShader(style: BackdropStyle): RuntimeShader? = remember(style) {
    try {
        RuntimeShader(HEADER + SOURCES.getValue(style))
    } catch (e: IllegalArgumentException) {
        Log.e("AnimatedBackground", "shader $style failed to compile", e)
        null
    }
}

/** Pre-Android 13: drifting soft colour blobs on the canvas. */
@Composable
private fun FallbackBlobs(p: Palette, time: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        val t = time() * 0.15f
        val r = size.maxDimension * 0.55f
        listOf(Triple(0.3f, 0.25f, p.a), Triple(0.75f, 0.55f, p.b), Triple(0.4f, 0.85f, lerp(p.a, p.b, 0.5f))).forEachIndexed { i, (x, y, c) ->
            val center = Offset(
                size.width * (x + 0.18f * sin(t * (1.1f + i * 0.3f) + i)),
                size.height * (y + 0.12f * cos(t * (0.9f + i * 0.2f) + i * 2)),
            )
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center, r), r, center)
        }
    }
}

private const val HEADER = """
uniform float2 iResolution;
uniform float iTime;
uniform float3 cBase;
uniform float3 cA;
uniform float3 cB;
"""

private val SOURCES = mapOf(
    // Glossy silk: a layered sine height field lit with a diffuse + specular term.
    BackdropStyle.SILK to """
float h(float2 p, float t) {
    return sin(p.x * 2.2 + t + sin(p.y * 1.7 + t * 0.7) * 1.6) * 0.5
         + sin(p.y * 3.1 - t * 0.8 + sin(p.x * 2.3 - t * 0.5) * 1.2) * 0.3
         + sin((p.x + p.y) * 5.0 + t * 1.3) * 0.08;
}
half4 main(float2 fc) {
    float2 p = fc / iResolution.y * 2.6;
    float t = iTime * 0.35;
    float e = 0.01;
    float hx = h(p + float2(e, 0.0), t) - h(p - float2(e, 0.0), t);
    float hy = h(p + float2(0.0, e), t) - h(p - float2(0.0, e), t);
    float3 n = normalize(float3(-hx, -hy, 2.0 * e * 1.6));
    float3 L = normalize(float3(-0.5, 0.6, 0.65));
    float diff = clamp(dot(n, L), 0.0, 1.0);
    float spec = pow(clamp(dot(reflect(-L, n), float3(0.0, 0.0, 1.0)), 0.0, 1.0), 18.0);
    float3 col = mix(cBase, cA, smoothstep(-0.9, 0.9, h(p, t)));
    col = col * (0.45 + 0.75 * diff) + cB * spec * 0.5;
    return half4(half3(col), 1.0);
}
""",
    // Ink / aurora: domain-warped fractal noise with glowing filaments.
    BackdropStyle.INK to """
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
half4 main(float2 fc) {
    float2 p = fc / iResolution.y * 2.0;
    float t = iTime * 0.05;
    float2 q = float2(fbm(p + float2(0.0, t)), fbm(p + float2(5.2, 1.3) - t));
    float2 r = float2(fbm(p + 3.0 * q + float2(1.7, 9.2) + t * 1.5), fbm(p + 3.0 * q + float2(8.3, 2.8) - t * 1.2));
    float f = fbm(p + 3.5 * r);
    float3 col = mix(cBase, cA, smoothstep(0.3, 0.85, f));
    col = mix(col, cB, smoothstep(0.5, 1.0, f * length(r)) * 0.7);
    col += cB * smoothstep(0.025, 0.0, abs(f - 0.6)) * 0.3;
    return half4(half3(col), 1.0);
}
""",
    // Soft colour flow: drifting light pools on a gently warped gradient.
    BackdropStyle.FLOW to """
half4 main(float2 fc) {
    float2 p = fc / iResolution.y;
    float aspect = iResolution.x / iResolution.y;
    float t = iTime * 0.12;
    float g = p.x * 0.5 + p.y * 0.7 + 0.12 * sin(p.y * 3.0 + t * 2.0) + 0.08 * sin(p.x * 5.0 - t * 1.6);
    float3 col = mix(cBase, cA, smoothstep(0.1, 1.1, g) * 0.55);
    float2 c1 = float2(aspect * (0.35 + 0.25 * sin(t * 1.1)), 0.30 + 0.15 * cos(t * 0.9));
    float2 c2 = float2(aspect * (0.70 + 0.20 * cos(t * 0.8 + 1.0)), 0.65 + 0.15 * sin(t * 1.2 + 2.0));
    float2 c3 = float2(aspect * (0.45 + 0.30 * sin(t * 0.6 + 4.0)), 0.95 + 0.10 * cos(t * 0.7));
    col = mix(col, cA, exp(-dot(p - c1, p - c1) * 7.0) * 0.8);
    col = mix(col, cB, exp(-dot(p - c2, p - c2) * 6.0) * 0.6);
    col = mix(col, mix(cA, cB, 0.5), exp(-dot(p - c3, p - c3) * 5.0) * 0.6);
    return half4(half3(col), 1.0);
}
""",
    // Diagonal ribbons with shaded edges, slowly undulating.
    BackdropStyle.RIBBONS to """
half4 main(float2 fc) {
    float2 p = fc / iResolution.y;
    float t = iTime * 0.25;
    float w = p.x * 0.8 + p.y * 1.2 + 0.18 * sin(p.x * 3.0 + t) + 0.12 * sin(p.y * 4.0 - t * 0.8);
    float s = sin((w * 6.0 - t * 0.5) * 3.14159);
    float shade = smoothstep(-1.0, 1.0, s);
    float edge = pow(1.0 - abs(s), 8.0);
    float3 col = mix(cBase, cA, shade * 0.8);
    col = col * (0.75 + 0.25 * shade) + cB * edge * 0.25;
    return half4(half3(col), 1.0);
}
""",
)

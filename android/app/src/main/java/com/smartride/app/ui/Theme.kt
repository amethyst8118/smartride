package com.smartride.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colour palette for one theme. Colours are ARGB longs so they are cheap to store and compare. */
data class AppTheme(
    val name: String,
    val panel: Long,
    val panelSoft: Long,
    val accent: Long,
    val accentSoft: Long,
    val border: Long,
    val textMain: Long,
    val textMuted: Long,
    val bgStart: Long,
    val bgEnd: Long,
    val isLight: Boolean,
)

object ThemeDefs {
    val themes = listOf(
        AppTheme("Sand", 0xD8FFFFFF, 0xF2FFFFFF, 0xFFC8956C, 0xFFDEB896, 0x73B8A89A, 0xFF1C1917, 0xFF78716C, 0xFFFAF8F5, 0xFFF5F0EB, true),
        AppTheme("Midnight", 0xE810131A, 0xFF121620, 0xFF7C8DB5, 0xFF6B7FA3, 0xE81E2538, 0xFFE8ECF4, 0xFF8B95A8, 0xFF0C1018, 0xFF06080D, false),
        AppTheme("Sage", 0xD8FFFFFF, 0xF2FFFFFF, 0xFF6B8F71, 0xFFA3C4A8, 0x884A6B50, 0xFF1A2E1C, 0xFF527960, 0xFFF0F5F0, 0xFFF5FAF5, true),
        AppTheme("Dusk", 0xD8FFFFFF, 0xF2FFFFFF, 0xFF9B7E94, 0xFFC4A8BC, 0x998B6B82, 0xFF2D1F29, 0xFF7A6472, 0xFFF8F2F6, 0xFFFCF5FA, true),
        AppTheme("Slate", 0xE8151A22, 0xFF171D26, 0xFF6BA3A0, 0xFF8FBFBC, 0xE81E2A30, 0xFFE4E8EC, 0xFF8A949E, 0xFF0E1418, 0xFF070A0D, false),
        AppTheme("Lavender", 0xD8FFFFFF, 0xF2FFFFFF, 0xFF8E7BA8, 0xFFB8A8CC, 0x886B5A82, 0xFF231D2B, 0xFF6E6080, 0xFFF4F0F8, 0xFFF8F5FB, true),
        AppTheme("Ember", 0xE8161210, 0xFF1A1512, 0xFFB8865A, 0xFFD4A67A, 0xE82A2218, 0xFFEDE6DC, 0xFF9A8876, 0xFF12100C, 0xFF0A0907, false),
        AppTheme("Frost", 0xD8FFFFFF, 0xF2FFFFFF, 0xFF6A8FA8, 0xFFA0C0D4, 0x884A6E82, 0xFF1A2830, 0xFF527A8C, 0xFFEFF5F8, 0xFFF5FAFC, true),
        AppTheme("Earl Grey", 0xE81A1A1A, 0xFF222222, 0xFF8A8A8A, 0xFFA3A3A3, 0xE82D2D2D, 0xFFE0E0E0, 0xFF707070, 0xFF141414, 0xFF0D0D0D, false),
        AppTheme("Oatmeal", 0xD8FFFFFF, 0xF2FFFFFF, 0xFFC2B6A3, 0xFFD8D0C3, 0x888C7E6A, 0xFF2A2724, 0xFF968B7A, 0xFFF7F5F2, 0xFFFCFBFA, true),
        AppTheme("Oxford", 0xE80D131F, 0xFF141A29, 0xFF5A7599, 0xFF7A93B5, 0xE81C2638, 0xFFDCE4F0, 0xFF6B84A3, 0xFF0A0F1A, 0xFF05080E, false),
        AppTheme("Forest", 0xE8111A13, 0xFF18241A, 0xFF4A7555, 0xFF6B9375, 0xE8223326, 0xFFDCE8DF, 0xFF588563, 0xFF0E1410, 0xFF070A08, false),
    )
}

fun Long.toColor() = Color(this.toInt())

val ColorOk = Color(0xFF6B8F71)
val ColorWarn = Color(0xFFC8956C)
val ColorDanger = Color(0xFFBC6B6B)

/** Speed bands for route colouring, tuned for urban two-wheeler speeds. */
data class SpeedBand(val maxKmh: Double, val color: Color, val label: String)

val SPEED_BANDS = listOf(
    SpeedBand(15.0, Color(0xFF6B8F71), "<15"),
    SpeedBand(30.0, Color(0xFF6A8FA8), "15-30"),
    SpeedBand(45.0, Color(0xFFC8956C), "30-45"),
    SpeedBand(Double.MAX_VALUE, Color(0xFF9B7E94), ">45"),
)

fun speedColor(kmh: Double): Color = SPEED_BANDS.first { kmh < it.maxKmh }.color

val CHART_COLORS = listOf(
    Color(0xDD7C8DB5), Color(0xDD6B8F71), Color(0xDDC8956C),
    Color(0xDD9B7E94), Color(0xDD8B95A8), Color(0xDDA3C4A8),
)

/** The frosted-glass card look used by every panel. */
@Composable
fun Modifier.frostedGlassPanel(theme: AppTheme, radius: Float = 24f): Modifier {
    val isLight = theme.isLight
    return this
        .shadow(
            elevation = if (isLight) 4.dp else 8.dp,
            shape = RoundedCornerShape(radius.dp),
            ambientColor = Color.Black.copy(alpha = if (isLight) 0.02f else 0.15f),
            spotColor = Color.Black.copy(alpha = if (isLight) 0.03f else 0.2f),
        )
        .clip(RoundedCornerShape(radius.dp))
        .background(theme.panel.toColor().copy(alpha = if (isLight) 0.85f else 0.5f))
        .border(
            width = 0.5.dp,
            color = (if (isLight) theme.border.toColor() else Color.White).copy(alpha = if (isLight) 0.6f else 0.15f),
            shape = RoundedCornerShape(radius.dp),
        )
}

package com.smartride.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

@Composable
fun LiveCardGlass(title: String, tag: String, th: AppTheme, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.frostedGlassPanel(th, radius = 22f)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = th.textMain.toColor())
                Box(
                    modifier = Modifier
                        .background(th.accent.toColor().copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(0.5.dp, th.accent.toColor().copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(tag, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = th.accent.toColor())
                }
            }
            content()
        }
    }
}

@Composable
fun PanelHeader(title: String, subtitle: String, th: AppTheme) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold, color = th.textMain.toColor().copy(alpha = 0.85f))
        Text(subtitle, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = th.accent.toColor())
    }
}

@Composable
fun DashboardMetricNode(
    label: String, value: String, th: AppTheme, modifier: Modifier = Modifier,
    valueColor: Color? = null, compact: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(th.panelSoft.toColor().copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .border(0.5.dp, th.border.toColor().copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor().copy(alpha = 0.6f), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.ExtraBold,
                color = valueColor ?: th.textMain.toColor(), maxLines = 1)
        }
    }
}

@Composable
fun MetricsNodeGlass(label: String, value: String, th: AppTheme, modifier: Modifier = Modifier) {
    val base = if (th.isLight) th.panelSoft.toColor() else Color.White
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(listOf(base.copy(alpha = if (th.isLight) 0.6f else 0.15f), base.copy(alpha = if (th.isLight) 0.3f else 0.05f))),
                RoundedCornerShape(12.dp),
            )
            .border(0.5.dp, (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = if (th.isLight) 0.5f else 0.15f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor().copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = th.textMain.toColor())
        }
    }
}

@Composable
fun RidePill(text: String, primary: Boolean, th: AppTheme, bold: Boolean = true) {
    val neutral = if (th.isLight) th.panelSoft.toColor() else Color.White
    Box(
        modifier = Modifier
            .background(if (primary) th.accent.toColor().copy(alpha = 0.15f) else neutral.copy(alpha = if (th.isLight) 0.5f else 0.04f), CircleShape)
            .border(
                0.5.dp,
                if (primary) th.accent.toColor().copy(alpha = 0.3f) else (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = if (th.isLight) 0.2f else 0.08f),
                CircleShape,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 9.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = th.textMain.toColor().copy(alpha = 0.8f))
    }
}

@Composable
fun FilterPill(text: String, selected: Boolean, th: AppTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) th.accent.toColor() else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 10.sp, color = if (selected) th.bgStart.toColor() else th.textMuted.toColor(), fontWeight = FontWeight.Bold)
    }
}

/** Small coloured status chip ("SIMULATED", "NO GNSS FIX", "CONNECTED"...). */
@Composable
fun StatusBadge(text: String, color: Color, icon: ImageVector? = null) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
            Text(text, fontSize = 9.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 0.5.sp)
        }
    }
}

/** Pull-to-refresh indicator: a road with dashed lane markings and a two-wheeler riding down it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadRefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean, th: AppTheme, modifier: Modifier = Modifier) {
    val fraction = state.distanceFraction.coerceIn(0f, 1f)
    val alpha by animateFloatAsState(if (isRefreshing) 1f else fraction, tween(200), label = "roadAlpha")
    val bikeY = if (isRefreshing) 70.dp else lerp((-40).dp, 70.dp, fraction)
    Box(modifier.fillMaxWidth().height(170.dp).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val roadW = 90.dp.toPx()
            val left = (size.width - roadW) / 2
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF202020), Color(0xFF202020), Color.Transparent)),
                topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(roadW, size.height),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()), phase = -fraction * 120.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.8f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx(), pathEffect = dash)
        }
        Icon(
            Icons.Rounded.TwoWheeler, contentDescription = null, tint = th.accent.toColor(),
            modifier = Modifier.offset(y = bikeY).size(44.dp).background(th.panel.toColor(), CircleShape).padding(6.dp),
        )
        if (isRefreshing) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    .background(th.accent.toColor().copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("SYNCING", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp) }
        }
    }
}

object Format {
    private val zone get() = java.time.ZoneId.systemDefault()
    private val dateFmt = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy")
    private val timeFmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
    private val monthFmt = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")

    private fun at(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone)
    fun date(epoch: Long): String = at(epoch).format(dateFmt)
    fun time(epoch: Long): String = at(epoch).format(timeFmt)
    fun month(epoch: Long): String = at(epoch).format(monthFmt)

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }

    fun clock(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    fun km(meters: Double, decimals: Int = 2) = "%.${decimals}f".format(meters / 1000.0)
}

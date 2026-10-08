package com.smartride.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas

/** Distance per day as rounded bars; tap or drag to read a value. */
@Composable
fun WeeklyBarChart(data: List<Pair<String, Float>>, th: AppTheme, modifier: Modifier = Modifier) {
    if (data.isEmpty() || data.all { it.second == 0f }) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No rides in this period.", color = th.textMuted.toColor(), fontSize = 10.sp) }
        return
    }
    val maxVal = data.maxOf { it.second }.coerceAtLeast(0.1f)
    var focus by remember { mutableIntStateOf(-1) }

    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(th.accent.toColor().copy(alpha = 0.04f), th.accent.toColor().copy(alpha = 0.14f))))
                .pointerInput(data) {
                    detectTapGestures(onPress = { focus = -1 }) { o -> focus = (o.x / (size.width.toFloat() / data.size)).toInt().coerceIn(0, data.size - 1) }
                }
                .pointerInput(data) {
                    detectDragGestures(onDragEnd = { focus = -1 }) { ch, _ -> focus = (ch.position.x / (size.width.toFloat() / data.size)).toInt().coerceIn(0, data.size - 1) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val areaW = size.width / data.size
                val barW = areaW * 0.45f
                data.forEachIndexed { i, (_, v) ->
                    val h = (v / maxVal) * (size.height * 0.85f)
                    val x = i * areaW + (areaW - barW) / 2
                    drawRoundRect(
                        color = if (i == focus) (if (th.isLight) Color.Black else Color.White) else CHART_COLORS[i % CHART_COLORS.size].copy(alpha = 0.85f),
                        topLeft = Offset(x, size.height - h - 4.dp.toPx()),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2, barW / 2),
                    )
                }
            }
            if (focus in data.indices) {
                val f = data[focus]
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .border(1.dp, th.accent.toColor(), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text("${f.first}: ${"%.1f".format(f.second)} km", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val step = maxOf(1, data.size / 6)
            data.forEachIndexed { i, (label, _) ->
                if (i % step == 0) Text(label, fontSize = 8.sp, color = th.textMuted.toColor(), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "8 AM" -> 8, "12 AM" -> 0, "12 PM" -> 12 (labels produced by Analytics). */
private fun hourOf(label: String): Int {
    val h = label.substringBefore(' ').toIntOrNull() ?: 0
    val pm = label.endsWith("PM")
    return (h % 12) + if (pm) 12 else 0
}

/**
 * Rides started per hour of day on a 24-hour clock face: each hour has a fixed
 * slice (midnight at the top, noon at the bottom) whose length shows how many
 * rides started then. Tap a slice or a legend row to read it.
 */
@Composable
fun TimeOfDayChart(data: List<Pair<String, Int>>, th: AppTheme, modifier: Modifier = Modifier) {
    if (data.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No rides in this period.", color = th.textMuted.toColor(), fontSize = 10.sp) }
        return
    }
    val byHour = remember(data) { data.associate { (label, n) -> hourOf(label) to n } }
    val maxVal = remember(data) { data.maxOf { it.second }.toFloat().coerceAtLeast(1f) }
    var selected by remember { mutableIntStateOf(-1) }  // hour, or -1
    val accent = th.accent.toColor()
    val guide = (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = 0.12f)
    val labelColor = th.textMuted.toColor()

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(data) {
                    detectTapGestures { o ->
                        val deg = Math.toDegrees(atan2((o.y - size.height / 2f).toDouble(), (o.x - size.width / 2f).toDouble())).toFloat()
                        val fromTop = (deg + 90f + 360f) % 360f
                        val hour = (fromTop / 15f).toInt().coerceIn(0, 23)
                        selected = if (selected == hour || byHour[hour] == null) -1 else hour
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outer = minOf(cx, cy) * 0.80f   // room outside the ring for hour labels
                    val inner = outer * 0.12f
                    // clock face: rings and quarter ticks
                    for (f in listOf(0.5f, 1f)) drawCircle(guide, inner + (outer - inner) * f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    for (h in 0 until 24 step 6) {
                        val a = Math.toRadians((h * 15 - 90).toDouble())
                        drawLine(guide, Offset(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat()),
                            Offset(cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat()), 1.dp.toPx())
                    }
                    // one slice per hour, length = rides that hour
                    for ((h, n) in byHour) {
                        val r = inner + (outer - inner) * (n / maxVal)
                        val focus = h == selected
                        drawArc(
                            color = if (focus || selected == -1) accent else accent.copy(alpha = 0.35f),
                            startAngle = h * 15f - 90f + 1f, sweepAngle = 13f, useCenter = true,
                            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                        )
                    }
                    drawCircle(if (th.isLight) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.55f), inner, Offset(cx, cy))
                    // 12 / 6 / 12 / 6 markers
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        color = labelColor.toArgb()
                    }
                    listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p").forEach { (h, t) ->
                        val a = Math.toRadians((h * 15 - 90).toDouble())
                        val rr = outer + 11.dp.toPx()
                        drawContext.canvas.nativeCanvas.drawText(t, cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat() + paint.textSize / 3, paint)
                    }
                }
            }
            val sel = byHour[selected]
            if (sel != null) {
                val label = data.first { hourOf(it.first) == selected }.first
                Text("$sel ride${if (sel == 1) "" else "s"} started around $label", fontSize = 10.sp, color = th.textMain.toColor(),
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
            } else Spacer(Modifier.height(14.dp))
        }
        Column(
            Modifier.weight(1.1f).fillMaxHeight().padding(start = 10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            data.sortedBy { hourOf(it.first) }.forEach { (label, n) ->
                val h = hourOf(label)
                val isSel = h == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (isSel) accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { selected = if (isSel) -1 else h }
                        .padding(4.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = if (isSel || selected == -1) 1f else 0.35f)))
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) th.textMain.toColor() else th.textMuted.toColor(), modifier = Modifier.weight(1f))
                    Text("$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor())
                }
            }
        }
    }
}

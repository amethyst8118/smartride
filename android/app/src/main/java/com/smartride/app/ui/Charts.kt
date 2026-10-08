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

/** Rides started per hour of day: polar "rose" chart plus a scrollable legend. */
@Composable
fun TimeOfDayChart(data: List<Pair<String, Int>>, th: AppTheme, modifier: Modifier = Modifier) {
    if (data.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("No rides in this period.", color = th.textMuted.toColor(), fontSize = 10.sp) }
        return
    }
    var selected by remember { mutableIntStateOf(-1) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(data) {
                    detectTapGestures { o ->
                        val deg = Math.toDegrees(atan2((o.y - size.height / 2f).toDouble(), (o.x - size.width / 2f).toDouble())).toFloat()
                        val fromTop = (deg + 90f + 360f) % 360f
                        val sector = (fromTop / (360f / data.size)).toInt().coerceIn(0, data.size - 1)
                        selected = if (selected == sector) -1 else sector
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val maxVal = data.maxOf { it.second }.toFloat().coerceAtLeast(1f)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val maxR = minOf(cx, cy) * 0.9f
                    val sweep = 360f / data.size
                    data.forEachIndexed { i, (_, n) ->
                        val r = n / maxVal * maxR
                        val col = CHART_COLORS[i % CHART_COLORS.size]
                        drawArc(
                            color = if (i == selected) col else col.copy(alpha = 0.5f),
                            startAngle = -90f + i * sweep, sweepAngle = sweep, useCenter = true,
                            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                        )
                    }
                }
            }
            if (selected in data.indices) {
                val s = data[selected]
                Text(
                    "${s.second} ride${if (s.second == 1) "" else "s"} started around ${s.first}",
                    fontSize = 10.sp, color = th.textMain.toColor(), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            } else Spacer(Modifier.height(14.dp))
        }
        Column(
            Modifier.weight(1.1f).fillMaxHeight().padding(start = 10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            data.forEachIndexed { i, (label, n) ->
                val isSel = i == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (isSel) (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { selected = if (isSel) -1 else i }
                        .padding(4.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CHART_COLORS[i % CHART_COLORS.size]))
                    Spacer(Modifier.width(8.dp))
                    Text("$label ($n)", fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) th.textMain.toColor() else th.textMuted.toColor())
                }
            }
        }
    }
}

package com.smartride.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartride.app.CrashAlert
import com.smartride.app.data.Settings

/**
 * Full-screen crash alert: the rider has [Settings.crashCountdownS] seconds to
 * cancel before the emergency contact would be notified (report: "Cancel False
 * Crash Alert" use case -- keeps the human in the loop).
 */
@Composable
fun CrashAlertOverlay(alert: CrashAlert, settings: Settings, th: AppTheme, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.75f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
    val red = Color(0xFFD9534F)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { },  // swallow touches
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Warning, null, tint = red.copy(alpha = pulse), modifier = Modifier.size(56.dp))
            Text(if (alert.escalated) "Emergency alert" else "Crash detected", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)

            if (!alert.escalated) {
                Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                    val frac = alert.secondsLeft / settings.crashCountdownS.toFloat()
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 12.dp.toPx()
                        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                        drawArc(red, -90f, 360f * frac, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Text("${alert.secondsLeft}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                }
                Text(
                    buildString {
                        append("Impact")
                        if (alert.peakG > 0) append(" of %.1f g".format(alert.peakG))
                        append(", fall and stillness were detected by the on-board unit.\n")
                        append(
                            if (settings.emergencyNumber.isNotBlank()) "${settings.emergencyName.ifBlank { "Your emergency contact" }} will be alerted when the countdown ends."
                            else "Set an emergency contact in Settings."
                        )
                    },
                    color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onCancel, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("I'M OK — CANCEL", fontSize = 18.sp, fontWeight = FontWeight.Black) }
            } else {
                Text(
                    "No response from the rider.\n\n" +
                        "${settings.emergencyName.ifBlank { "The emergency contact" }}${if (settings.emergencyNumber.isNotBlank()) " (${settings.emergencyNumber})" else ""} " +
                        "would now receive your location:\n%.5f, %.5f\n\n".format(alert.lat, alert.lon) +
                        "Sending the SMS is not implemented in v0.1; the event has been logged.",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = th.accent.toColor()),
                ) { Text("Dismiss", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

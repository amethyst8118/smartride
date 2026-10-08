package com.smartride.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartride.app.BuildConfig
import com.smartride.app.SmartRideViewModel
import com.smartride.app.UiState

/** Full-screen settings ("rider console"), in the original app's console style. */
@Composable
fun SettingsScreen(s: UiState, th: AppTheme, backdrop: BackdropStyle, vm: SmartRideViewModel, onClose: () -> Unit) {
    val st = s.settings
    val c = s.connection
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(th, backdrop, animate = st.animatedBackground)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(36.dp).background(th.panelSoft.toColor().copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, th.border.toColor().copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = th.textMain.toColor(), modifier = Modifier.size(18.dp)) }
                Text("RIDER CONSOLE", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Black, color = th.accent.toColor())
            }

            // Rider capsule
            GlassCard(th) {
                Row(
                    Modifier.fillMaxWidth().clickable { edit = EditRequest("Your name", st.riderName) { v -> vm.updateSettings { it.copy(riderName = v.trim()) } } },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(th.accent.toColor().copy(alpha = 0.25f), th.accent.toColor().copy(alpha = 0.12f))))
                            .border(1.5.dp, th.accent.toColor().copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val initials = st.riderName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
                        Text(initials.ifEmpty { "R" }, fontSize = 20.sp, fontWeight = FontWeight.Black, color = th.accent.toColor())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(st.riderName.ifBlank { "Tap to set your name" }, fontSize = 16.sp, fontWeight = FontWeight.Black,
                            color = th.textMain.toColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (c.isReady) "Paired with ${c.deviceName ?: "SmartRide unit"}" else "No unit connected",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = th.textMuted.toColor().copy(alpha = 0.7f),
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = th.textMuted.toColor())
                }
            }

            GlassCard(th) {
                SectionHeader("ON-BOARD UNIT", th)
                InfoRow("Unit", c.deviceName ?: "—", th)
                InfoRow("Firmware", c.firmware ?: "—", th)
                InfoRow("Hardware", c.hardware ?: "—", th)
                InfoRow("Link", if (c.isReady) "BLE · MTU ${c.mtu}" else "not connected", th)
                InfoRow("Battery", s.live?.batteryPct?.let { p -> "$p %" + (s.live.batteryMv?.let { " · %.2f V".format(it / 1000.0) } ?: "") } ?: "—", th)
            }

            GlassCard(th) {
                SectionHeader("EMERGENCY CONTACT", th)
                EditRow("Name", st.emergencyName.ifBlank { "Not set" }, th) {
                    edit = EditRequest("Contact name", st.emergencyName) { v -> vm.updateSettings { it.copy(emergencyName = v.trim()) } }
                }
                EditRow("Phone", st.emergencyNumber.ifBlank { "Not set" }, th) {
                    edit = EditRequest("Phone number", st.emergencyNumber, KeyboardType.Phone) { v -> vm.updateSettings { it.copy(emergencyNumber = v.trim()) } }
                }
                StepperRow("Cancel window", "${st.crashCountdownS} s", th,
                    onMinus = { vm.updateSettings { it.copy(crashCountdownS = (it.crashCountdownS - 5).coerceAtLeast(10)) } },
                    onPlus = { vm.updateSettings { it.copy(crashCountdownS = (it.crashCountdownS + 5).coerceAtMost(120)) } })
                Note("After a crash is confirmed, this is how long you have to tap I'M OK before the contact is alerted.", th)
            }

            GlassCard(th) {
                SectionHeader("MAINTENANCE", th)
                EditRow("Service every", "${st.serviceIntervalKm} km", th) {
                    edit = EditRequest("Service interval (km)", st.serviceIntervalKm.toString(), KeyboardType.Number) { v ->
                        v.toIntOrNull()?.let { km -> vm.updateSettings { it.copy(serviceIntervalKm = km.coerceIn(100, 50_000)) } }
                    }
                }
                EditRow("Last service at", "${st.lastServiceOdoKm} km", th) {
                    edit = EditRequest("Odometer at last service (km)", st.lastServiceOdoKm.toString(), KeyboardType.Number) { v ->
                        v.toIntOrNull()?.let { km -> vm.updateSettings { it.copy(lastServiceOdoKm = km.coerceAtLeast(0)) } }
                    }
                }
                val used = ((s.odometerKm - st.lastServiceOdoKm) / st.serviceIntervalKm).toFloat().coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(th.border.toColor().copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(used).fillMaxHeight().clip(CircleShape)
                        .background(if (s.serviceDue) ColorDanger else th.accent.toColor()))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.serviceDue) "Service due" else "Next service in %.0f km".format(s.kmToService),
                        fontSize = 11.sp, color = if (s.serviceDue) ColorDanger else th.textMuted.toColor())
                    PillButton("Mark serviced", th, onClick = vm::markServiced)
                }
            }

            GlassCard(th) {
                SectionHeader("APPEARANCE", th)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeDefs.themes.forEachIndexed { i, t ->
                        val selected = t.name == th.name
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(t.bgStart.toColor(), t.accent.toColor())))
                                .border(if (selected) 2.dp else 0.5.dp, if (selected) th.textMain.toColor() else th.border.toColor().copy(alpha = 0.4f), CircleShape)
                                .clickable { vm.setTheme(i) },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, t.name, tint = t.textMain.toColor(), modifier = Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(th.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor())
                ToggleRow("Animated background", st.animatedBackground, th) { v -> vm.updateSettings { it.copy(animatedBackground = v) } }
            }

            GlassCard(th) {
                SectionHeader("DATA & TESTING", th)
                ToggleRow("Sensor test buttons", st.developerMode, th) { v -> vm.updateSettings { it.copy(developerMode = v) } }
                Note("Shows Simulate crash / Simulate pothole on the dashboard while the unit runs simulated sensors.", th)
                HorizontalDivider(color = th.border.toColor().copy(alpha = 0.15f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                ActionRow("Load demo rides", "Realistic rides without hardware", th, onClick = vm::loadDemoRides)
                ActionRow("Delete ride history", "${s.rides.size} rides on this phone", th, danger = true) { confirmClear = true }
            }

            GlassCard(th) {
                SectionHeader("ABOUT", th)
                InfoRow("App", "SmartRide ${BuildConfig.VERSION_NAME}", th)
                InfoRow("Project", "TKMIT CSE · Group 14", th)
                InfoRow("Maps", "© OpenStreetMap · OpenFreeMap", th)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    edit?.let { req ->
        EditDialog(req, th, onDismiss = { edit = null }) { v -> req.onSave(v); edit = null }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete ride history?") },
            text = { Text("All ${s.rides.size} rides and their routes will be removed from this phone. Rides the unit has already handed over cannot be synced again.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete", color = ColorDanger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = th.textMain.toColor()) } },
            containerColor = th.panelSoft.toColor(), titleContentColor = th.textMain.toColor(), textContentColor = th.textMuted.toColor(),
        )
    }
}

private class EditRequest(
    val title: String,
    val initial: String,
    val keyboard: KeyboardType = KeyboardType.Text,
    val onSave: (String) -> Unit,
)

@Composable
private fun EditDialog(req: EditRequest, th: AppTheme, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(req) { mutableStateOf(req.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(req.title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = if (req.keyboard == KeyboardType.Number) it.filter(Char::isDigit).take(6) else it },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = req.keyboard), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = th.textMain.toColor(), unfocusedTextColor = th.textMain.toColor(),
                    focusedBorderColor = th.accent.toColor(), unfocusedBorderColor = th.border.toColor().copy(alpha = 0.5f),
                    cursorColor = th.accent.toColor(),
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = th.accent.toColor(), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = th.textMuted.toColor()) } },
        containerColor = th.panelSoft.toColor(), titleContentColor = th.textMain.toColor(),
    )
}

@Composable
private fun GlassCard(th: AppTheme, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(
                th.panel.toColor().copy(alpha = if (th.isLight) 0.92f else 0.5f),
                th.panel.toColor().copy(alpha = if (th.isLight) 0.84f else 0.35f),
            )))
            .border(0.5.dp, Brush.linearGradient(listOf(
                (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = if (th.isLight) 0.6f else 0.2f),
                (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = if (th.isLight) 0.3f else 0.05f),
            )), RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionHeader(title: String, th: AppTheme) {
    Text(title, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Black, color = th.accent.toColor(),
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun InfoRow(label: String, value: String, th: AppTheme) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = th.textMuted.toColor().copy(alpha = 0.75f), modifier = Modifier.weight(0.4f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor(), textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun EditRow(label: String, value: String, th: AppTheme, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = th.textMuted.toColor().copy(alpha = 0.75f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor(), maxLines = 1)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = th.textMuted.toColor(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, th: AppTheme, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = th.textMuted.toColor().copy(alpha = 0.75f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIcon(Icons.Rounded.Remove, th, onMinus)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Black, color = th.textMain.toColor(), textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
            RoundIcon(Icons.Rounded.Add, th, onPlus)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, th: AppTheme, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(th.accent.toColor().copy(alpha = 0.15f))
            .border(0.5.dp, th.accent.toColor().copy(alpha = 0.35f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = th.accent.toColor(), modifier = Modifier.size(16.dp)) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, th: AppTheme, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = th.textMain.toColor(), modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = th.accent.toColor(),
                uncheckedThumbColor = th.textMuted.toColor(), uncheckedTrackColor = th.panelSoft.toColor(),
                uncheckedBorderColor = th.border.toColor().copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, th: AppTheme, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (danger) ColorDanger else th.textMain.toColor())
            Text(subtitle, fontSize = 10.sp, color = th.textMuted.toColor())
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = if (danger) ColorDanger else th.textMuted.toColor(), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PillButton(text: String, th: AppTheme, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(th.accent.toColor().copy(alpha = 0.15f))
            .border(0.5.dp, th.accent.toColor().copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = th.accent.toColor()) }
}

@Composable
private fun Note(text: String, th: AppTheme) {
    Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = th.textMuted.toColor().copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
}

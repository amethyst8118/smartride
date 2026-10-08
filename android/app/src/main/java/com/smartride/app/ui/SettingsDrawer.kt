package com.smartride.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartride.app.BuildConfig
import com.smartride.app.SmartRideViewModel
import com.smartride.app.UiState

@Composable
fun SettingsDrawer(s: UiState, th: AppTheme, vm: SmartRideViewModel, onClose: () -> Unit) {
    val st = s.settings
    var confirmClear by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.12f))
        Column(
            Modifier.weight(0.88f).fillMaxHeight().background(th.bgEnd.toColor())
                .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Black, color = th.textMain.toColor())
                Box(Modifier.size(36.dp).background(th.panelSoft.toColor().copy(alpha = 0.5f), CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = th.textMain.toColor())
                }
            }

            Section("Rider", th) {
                Field("Your name", st.riderName, th) { v -> vm.updateSettings { it.copy(riderName = v) } }
            }

            Section("Emergency contact", th) {
                Field("Contact name", st.emergencyName, th) { v -> vm.updateSettings { it.copy(emergencyName = v) } }
                Field("Phone number", st.emergencyNumber, th, KeyboardType.Phone) { v -> vm.updateSettings { it.copy(emergencyNumber = v) } }
                NumberField("Cancel countdown (seconds)", st.crashCountdownS, th) { v -> vm.updateSettings { it.copy(crashCountdownS = v.coerceIn(5, 120)) } }
            }

            Section("Maintenance", th) {
                NumberField("Service interval (km)", st.serviceIntervalKm, th) { v -> vm.updateSettings { it.copy(serviceIntervalKm = v.coerceIn(100, 50_000)) } }
                NumberField("Odometer at last service (km)", st.lastServiceOdoKm, th) { v -> vm.updateSettings { it.copy(lastServiceOdoKm = v.coerceAtLeast(0)) } }
                Text("Logged odometer: %.1f km. Next service in %.0f km.".format(s.odometerKm, s.kmToService), fontSize = 11.sp, color = th.textMuted.toColor())
                TextButton(onClick = vm::markServiced) { Text("Mark serviced now", color = th.accent.toColor(), fontWeight = FontWeight.Bold) }
            }

            Section("Appearance", th) {
                Text("Theme: ${th.name}", fontSize = 12.sp, color = th.textMain.toColor())
                TextButton(onClick = vm::shuffleTheme) { Text("Next theme", color = th.accent.toColor(), fontWeight = FontWeight.Bold) }
            }

            Section("Data", th) {
                Toggle("Developer mode (sensor test buttons)", st.developerMode, th) { v -> vm.updateSettings { it.copy(developerMode = v) } }
                TextButton(onClick = vm::loadDemoRides) { Text("Load demo rides (no hardware)", color = th.accent.toColor(), fontWeight = FontWeight.Bold) }
                TextButton(onClick = { confirmClear = true }) { Text("Delete ride history…", color = ColorDanger, fontWeight = FontWeight.Bold) }
            }

            Section("About", th) {
                Text(
                    "SmartRide ${BuildConfig.VERSION_NAME}\nTwo-wheeler ride logger and crash detection.\n" +
                        "TKM Institute of Technology, CSE Group 14.\n\n" +
                        "Map data © OpenStreetMap contributors (ODbL).",
                    fontSize = 11.sp, color = th.textMuted.toColor(), lineHeight = 16.sp,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete ride history?") },
            text = { Text("All ${s.rides.size} stored rides and their routes will be removed from this phone. Rides already acknowledged by the unit cannot be synced again.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete", color = ColorDanger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = th.panelSoft.toColor(), titleContentColor = th.textMain.toColor(), textContentColor = th.textMuted.toColor(),
        )
    }
}

@Composable
private fun Section(title: String, th: AppTheme, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().frostedGlassPanel(th, 20f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold, color = th.accent.toColor())
        content()
    }
}

@Composable
private fun Field(label: String, value: String, th: AppTheme, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = th.textMain.toColor(), unfocusedTextColor = th.textMain.toColor(),
            focusedBorderColor = th.accent.toColor(), unfocusedBorderColor = th.border.toColor().copy(alpha = 0.4f),
            focusedLabelColor = th.accent.toColor(), unfocusedLabelColor = th.textMuted.toColor(), cursorColor = th.accent.toColor(),
        ),
    )
}

@Composable
private fun NumberField(label: String, value: Int, th: AppTheme, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Field(label, text, th, KeyboardType.Number) { t ->
        text = t.filter(Char::isDigit).take(6)
        text.toIntOrNull()?.let(onChange)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, th: AppTheme, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = th.textMain.toColor(), modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = th.accent.toColor(), checkedThumbColor = Color.White,
                uncheckedTrackColor = th.border.toColor().copy(alpha = 0.3f), uncheckedBorderColor = th.border.toColor()),
        )
    }
}

package com.smartride.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.smartride.app.SmartRideViewModel
import com.smartride.app.SyncStatus
import com.smartride.app.UiState
import com.smartride.app.ble.SmartRideBleClient.Status
import com.smartride.app.ble.SmartRideProtocol.LivePacket
import com.smartride.app.data.db.RideEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRideRoot(vm: SmartRideViewModel, onRequestPermissions: () -> Unit, onEnableBluetooth: () -> Unit) {
    val s by vm.ui.collectAsState()
    val th = s.theme

    // System bar icon colours follow the theme.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = th.isLight
            isAppearanceLightNavigationBars = th.isLight
        }
    }

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val drawerOffset by animateFloatAsState(if (drawerOpen) 0f else 1f, tween(400, easing = EaseOutCubic), label = "drawer")
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // A new backdrop style is picked each time the theme changes, as in the original app.
    val backdrop = remember(th.name) { BackdropStyle.entries.random() }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(th, backdrop, animate = s.settings.animatedBackground)
        val refreshState = rememberPullToRefreshState()
        val syncing = s.sync is SyncStatus.Running
        PullToRefreshBox(
            isRefreshing = syncing,
            state = refreshState,
            onRefresh = { vm.syncNow() },
            indicator = { RoadRefreshIndicator(refreshState, syncing, th, Modifier.align(Alignment.TopCenter)) },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                TopBar(s, th, vm, onOpenSettings = { drawerOpen = true }, onRequestPermissions, onEnableBluetooth)
                SyncStatusLine(s, th, vm)
                DeviceStatusCard(s, th, vm)
                if (s.live?.hasPosition == true) LiveMapPanel(s, th)
                ChartsSection(s, th)
                HistoryAndDetails(s, th, vm)
                Spacer(Modifier.height(10.dp))
            }
        }

        // Settings: full-screen page sliding in from the right
        if (drawerOffset < 1f) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * drawerOffset }) {
                SettingsScreen(s, th, backdrop, vm, onClose = { drawerOpen = false })
            }
        }

        // Crash alert sits above everything.
        s.crashAlert?.let { CrashAlertOverlay(it, s.settings, th, onCancel = vm::cancelCrash, onDismiss = vm::dismissCrash) }
    }
}

// ═══════════════════ TOP BAR ═══════════════════

@Composable
private fun TopBar(
    s: UiState, th: AppTheme, vm: SmartRideViewModel,
    onOpenSettings: () -> Unit, onRequestPermissions: () -> Unit, onEnableBluetooth: () -> Unit,
) {
    val live = s.live
    Box(Modifier.fillMaxWidth().frostedGlassPanel(th, radius = 28f)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Hello,", fontSize = 11.sp, color = th.textMuted.toColor(), fontWeight = FontWeight.Medium)
                    Text(s.settings.riderName.ifBlank { "Rider" }, fontSize = 16.sp, fontWeight = FontWeight.Black, color = th.textMain.toColor())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.background(th.panelSoft.toColor().copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .border(0.5.dp, th.border.toColor().copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .clickable { vm.shuffleTheme() }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("SHUFFLE", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = th.textMain.toColor()) }
                    Box(
                        Modifier.size(36.dp).background(th.accent.toColor().copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, th.accent.toColor().copy(alpha = 0.3f), CircleShape).clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Settings, "Settings", tint = th.accent.toColor(), modifier = Modifier.size(18.dp)) }
                }
            }

            Spacer(Modifier.height(14.dp))
            ConnectionRow(s, th, onRequestPermissions, onEnableBluetooth)
            Spacer(Modifier.height(14.dp))

            // Telemetry hub: unit battery | speed + ride distance, then ride time / odometer / service
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatteryCard(live, th, Modifier.weight(1.3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val spd = live?.takeIf { it.gnssFix }?.speedKmh
                    DashboardMetricNode("SPEED", spd?.let { "%.0f km/h".format(it) } ?: "--", th, Modifier.fillMaxWidth(),
                        valueColor = spd?.let { speedColor(it) })
                    DashboardMetricNode("THIS RIDE", live?.takeIf { it.rideActive }?.let { "${Format.km(it.rideDistanceM.toDouble())} km" } ?: "--", th, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetricNode("RIDE TIME", live?.takeIf { it.rideActive }?.let { Format.clock(it.rideDurationS) } ?: "--", th, Modifier.weight(1f), compact = true)
                DashboardMetricNode("ODOMETER", "${Format.km(s.odometerKm * 1000, 1)} km", th, Modifier.weight(1f), compact = true)
                val left = s.kmToService
                DashboardMetricNode(
                    "SERVICE IN", if (s.serviceDue) "DUE" else "%.0f km".format(left), th, Modifier.weight(1f), compact = true,
                    valueColor = when { s.serviceDue -> ColorDanger; left < 200 -> ColorWarn; else -> null },
                )
            }
            if (s.serviceDue) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().background(ColorDanger.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                        .border(0.5.dp, ColorDanger.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Build, null, tint = ColorDanger, modifier = Modifier.size(18.dp))
                    Text("Service interval of ${s.settings.serviceIntervalKm} km reached.", fontSize = 11.sp, color = th.textMain.toColor(), modifier = Modifier.weight(1f))
                    Text("DONE", fontSize = 11.sp, fontWeight = FontWeight.Black, color = ColorDanger, modifier = Modifier.clickable { vm.markServiced() })
                }
            }
        }
    }
}

/** Unit battery in the original app's style: big percentage, cell glyph, charge bar. */
@Composable
private fun BatteryCard(live: LivePacket?, th: AppTheme, modifier: Modifier = Modifier) {
    val pct = live?.batteryPct
    val low = pct != null && pct < 20
    val cellColor = if (low) ColorDanger else ColorOk
    Box(
        modifier.background(th.panelSoft.toColor().copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .border(0.5.dp, th.border.toColor().copy(alpha = 0.1f), RoundedCornerShape(18.dp)).padding(12.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("BATTERY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor().copy(alpha = 0.6f))
                if (low) StatusBadge("LOW", ColorDanger)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pct?.let { "$it%" } ?: "--", fontSize = 30.sp, fontWeight = FontWeight.Black, color = th.textMain.toColor())
                Canvas(Modifier.padding(bottom = 9.dp).size(width = 26.dp, height = 13.dp)) {
                    val capW = 3.dp.toPx()
                    drawRoundRect(cellColor.copy(alpha = 0.7f), size = Size(size.width - capW, size.height),
                        cornerRadius = CornerRadius(2.5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    drawRect(cellColor.copy(alpha = 0.7f), topLeft = Offset(size.width - capW, size.height * 0.28f),
                        size = Size(capW, size.height * 0.44f))
                    val inset = 2.5.dp.toPx()
                    drawRoundRect(cellColor, topLeft = Offset(inset, inset),
                        size = Size((size.width - capW - 2 * inset) * ((pct ?: 0) / 100f), size.height - 2 * inset),
                        cornerRadius = CornerRadius(1.dp.toPx()))
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    .background((if (th.isLight) th.border.toColor() else Color.White).copy(alpha = 0.15f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(((pct ?: 0) / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape)
                        .background(if (low) ColorDanger else th.accent.toColor()),
                )
            }
            Spacer(Modifier.height(10.dp))
            val (status, statusColor) = when {
                live == null -> "Not connected" to th.textMuted.toColor()
                pct == null -> "Not measured" to th.textMuted.toColor()
                live.batteryCharging && pct >= 99 -> "Fully charged" to ColorOk
                live.batteryCharging -> "⚡ Charging" to ColorOk
                else -> "Discharging" to th.textMuted.toColor()
            }
            Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
        }
    }
}

@Composable
private fun ConnectionRow(s: UiState, th: AppTheme, onRequestPermissions: () -> Unit, onEnableBluetooth: () -> Unit) {
    val c = s.connection
    val (label, color, icon) = when {
        !s.permissionsGranted -> Triple("BLUETOOTH PERMISSION NEEDED", ColorWarn, Icons.Rounded.BluetoothDisabled)
        c.status == Status.BLUETOOTH_DISABLED -> Triple("BLUETOOTH IS OFF", ColorWarn, Icons.Rounded.BluetoothDisabled)
        c.status == Status.CONNECTED -> Triple("CONNECTED · ${c.deviceName ?: "SmartRide"}", ColorOk, Icons.Rounded.Bluetooth)
        c.status == Status.CONNECTING -> Triple("CONNECTING…", th.accent.toColor(), Icons.AutoMirrored.Rounded.BluetoothSearching)
        c.status == Status.SCANNING -> Triple("SEARCHING FOR UNIT…", th.accent.toColor(), Icons.AutoMirrored.Rounded.BluetoothSearching)
        c.status == Status.DISCONNECTED -> Triple("DISCONNECTED · RECONNECTING", ColorWarn, Icons.AutoMirrored.Rounded.BluetoothSearching)
        else -> Triple("NOT CONNECTED", th.textMuted.toColor(), Icons.Rounded.BluetoothDisabled)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusBadge(label, color, icon)
        s.live?.let { l ->
            if (l.simulated) StatusBadge("SIMULATED SENSORS", Color(0xFF8E7BA8), Icons.Rounded.Science)
            if (l.gnssFix) StatusBadge("GNSS ${l.sats} SATS", ColorOk, Icons.Rounded.GpsFixed) else StatusBadge("NO GNSS FIX", ColorDanger, Icons.Rounded.GpsOff)
        }
    }
    if (!s.permissionsGranted) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequestPermissions, shape = RoundedCornerShape(12.dp)) {
            Text("Allow Bluetooth access", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor())
        }
    } else if (c.status == Status.BLUETOOTH_DISABLED) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onEnableBluetooth, shape = RoundedCornerShape(12.dp)) {
            Text("Turn on Bluetooth", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor())
        }
    }
}

@Composable
private fun SyncStatusLine(s: UiState, th: AppTheme, vm: SmartRideViewModel) {
    val text = when (val st = s.sync) {
        SyncStatus.Idle -> null
        is SyncStatus.Running -> if (st.total > 0) "Syncing ride logs from ${st.label}… ${st.done}/${st.total}" else "Syncing ride logs from ${st.label}…"
        is SyncStatus.Done -> with(st.report) {
            when {
                errors.isNotEmpty() -> "Synced $added ride${if (added == 1) "" else "s"} from $source, ${errors.size} failed (${errors.first()})"
                added > 0 -> "Synced $added new ride${if (added == 1) "" else "s"} from $source"
                else -> "Up to date with $source"
            }
        }
        is SyncStatus.Failed -> "Sync failed: ${st.message}"
    }
    AnimatedVisibility(text != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val failed = s.sync is SyncStatus.Failed || (s.sync as? SyncStatus.Done)?.report?.errors?.isNotEmpty() == true
        Row(
            Modifier.fillMaxWidth().frostedGlassPanel(th, 16f).clickable { vm.clearSyncStatus() }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.sync is SyncStatus.Running) CircularProgressIndicator(Modifier.size(14.dp), color = th.accent.toColor(), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Sync, null, tint = if (failed) ColorDanger else ColorOk, modifier = Modifier.size(16.dp))
            Text(text ?: "", fontSize = 11.sp, color = th.textMain.toColor(), modifier = Modifier.weight(1f))
        }
    }
}

// ═══════════════════ DEVICE STATUS + CONTROLS ═══════════════════

@Composable
private fun DeviceStatusCard(s: UiState, th: AppTheme, vm: SmartRideViewModel) {
    val c = s.connection
    val live = s.live
    LiveCardGlass("On-board unit", if (c.isReady) "LIVE" else "OFFLINE", th, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow("Unit", c.deviceName ?: "—", th)
            StatusRow("Firmware", listOfNotNull(c.firmware, c.hardware).joinToString(" · ").ifEmpty { "—" }, th)
            StatusRow("Link MTU", if (c.isReady) "${c.mtu} B" else "—", th)
            StatusRow("Clock", when { live == null -> "—"; live.timeSynced -> "synced with phone"; else -> "not synced" }, th)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val active = live?.rideActive == true
            Button(
                onClick = { if (active) vm.stopRide() else vm.startRide() }, enabled = c.isReady,
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (active) ColorDanger else th.accent.toColor(), contentColor = Color.White),
            ) {
                Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (active) "Stop ride" else "Start ride", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            OutlinedButton(onClick = vm::syncNow, enabled = c.isReady, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = th.textMain.toColor())
                Spacer(Modifier.width(6.dp))
                Text("Sync logs", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = th.textMain.toColor())
            }
        }
        if (s.settings.developerMode) {
            Spacer(Modifier.height(10.dp))
            Text("TEST INPUTS (simulated sensors)", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = th.accent.toColor())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::simulateCrash, enabled = c.isReady, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Simulate crash", fontSize = 12.sp, color = ColorDanger, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = vm::simulatePothole, enabled = c.isReady, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Simulate pothole", fontSize = 12.sp, color = th.textMain.toColor(), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, th: AppTheme, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = th.textMuted.toColor())
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = valueColor ?: th.textMain.toColor())
    }
}

// ═══════════════════ LIVE MAP ═══════════════════

@Composable
private fun LiveMapPanel(s: UiState, th: AppTheme) {
    val live = s.live ?: return
    Box(Modifier.fillMaxWidth().frostedGlassPanel(th, radius = 24f)) {
        Column(Modifier.padding(16.dp)) {
            PanelHeader("Live position", if (live.rideActive) "Recording" else "Not recording", th)
            Spacer(Modifier.height(12.dp))
            RouteMap(
                path = s.liveTrail.map { MapPoint(it.lat, it.lon, it.speedKmh) },
                position = MapPoint(live.lat, live.lon, live.speedKmh),
                followPosition = true, showEndpoints = false, th = th,
                modifier = Modifier.fillMaxWidth().height(240.dp)
                    .border(0.5.dp, th.border.toColor().copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .graphicsLayer { clip = true; shape = RoundedCornerShape(16.dp) },
            )
            Spacer(Modifier.height(8.dp))
            SpeedLegend(th)
        }
    }
}

@Composable
private fun SpeedLegend(th: AppTheme) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SPEED_BANDS.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).background(b.color, CircleShape))
                Text("${b.label} km/h", fontSize = 9.sp, color = th.textMuted.toColor())
            }
        }
    }
}

// ═══════════════════ CHARTS ═══════════════════

@Composable
private fun ChartsSection(s: UiState, th: AppTheme) {
    var weekly by rememberSaveable { mutableStateOf(true) }
    LiveCardGlass("Distance by day", if (weekly) "LAST 7 DAYS" else "ALL TIME", th, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Week", weekly, th) { weekly = true }
            FilterPill("All", !weekly, th) { weekly = false }
        }
        WeeklyBarChart(if (weekly) s.analytics.weekKm else s.analytics.allKm, th, Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp))
    }
    var hoursWeek by rememberSaveable { mutableStateOf(true) }
    LiveCardGlass("Time of day", if (hoursWeek) "LAST 7 DAYS" else "ALL TIME", th, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Week", hoursWeek, th) { hoursWeek = true }
            FilterPill("All", !hoursWeek, th) { hoursWeek = false }
        }
        TimeOfDayChart(if (hoursWeek) s.analytics.weekHours else s.analytics.allHours, th, Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp))
    }
}

// ═══════════════════ HISTORY + DETAILS ═══════════════════

@Composable
private fun HistoryAndDetails(s: UiState, th: AppTheme, vm: SmartRideViewModel) {
    Box(Modifier.fillMaxWidth().frostedGlassPanel(th)) {
        Column(Modifier.padding(16.dp)) {
            PanelHeader("Ride history", "${s.rides.size} ride${if (s.rides.size == 1) "" else "s"}", th)
            Spacer(Modifier.height(12.dp))
            if (s.rides.isEmpty()) {
                Text(
                    "No rides yet. Connect to the on-board unit and its ride logs sync automatically, or load demo rides to explore without hardware.",
                    fontSize = 11.sp, color = th.textMuted.toColor(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::syncNow, enabled = s.connection.isReady, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = th.accent.toColor())) { Text("Sync from unit", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = vm::loadDemoRides, shape = RoundedCornerShape(12.dp)) {
                        Text("Load demo rides", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor())
                    }
                }
            } else {
                // Not lazy: the page itself scrolls and the list is short (tens of rides).
                Column(Modifier.heightIn(max = 2000.dp)) {
                    s.rides.groupBy { Format.month(it.startEpoch) }.forEach { (month, rides) ->
                        Text(month.uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold,
                            color = th.accent.toColor().copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                        rides.forEach { r ->
                            RideRow(r, r.id == s.selectedRideId, th) { vm.selectRide(r.id) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    AnimatedVisibility(s.selectedRide != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val ride = s.selectedRide ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().frostedGlassPanel(th)) {
            Column(Modifier.padding(16.dp)) {
                PanelHeader("Ride details", Format.dayDate(ride.startEpoch), th)
                Spacer(Modifier.height(14.dp))
                RideDetails(ride, s, th, vm)
            }
        }
    }
}

@Composable
private fun RideRow(ride: RideEntity, selected: Boolean, th: AppTheme, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) th.accent.toColor().copy(alpha = 0.15f) else th.panelSoft.toColor().copy(alpha = 0.25f), label = "rowBg")
    val border by animateColorAsState(if (selected) th.accent.toColor().copy(alpha = 0.4f) else th.border.toColor().copy(alpha = 0.06f), label = "rowBorder")
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp))
            .background(bg).border(0.5.dp, border, RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        // Accent strip in the ride's average-speed colour (same scale as the route map).
        Box(Modifier.width(4.dp).fillMaxHeight().background(speedColor(ride.avgSpeedKmh)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(Format.dayDate(ride.startEpoch), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = th.textMain.toColor())
                    Text(Format.timeRange(ride.startEpoch, ride.endEpoch), fontSize = 11.sp, color = th.textMuted.toColor())
                }
                Text(Format.duration(ride.durationS), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = th.textMain.toColor())
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = th.border.toColor().copy(alpha = 0.12f), thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                RideStat(Format.km(ride.distanceM, 1), "km", "DISTANCE", th, Modifier.weight(1f))
                RideStat("%.0f".format(ride.avgSpeedKmh), "km/h", "AVERAGE", th, Modifier.weight(1f))
                RideStat("%.0f".format(ride.maxSpeedKmh), "km/h", "TOP SPEED", th, Modifier.weight(1f))
            }
        }
    }
}

/** Big number with a small unit, and a quiet caption underneath. */
@Composable
private fun RideStat(value: String, unit: String, caption: String, th: AppTheme, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = th.textMain.toColor())
            Text(unit, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = th.textMuted.toColor(), modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(caption, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor().copy(alpha = 0.7f))
    }
}

@Composable
private fun RideDetails(ride: RideEntity, s: UiState, th: AppTheme, vm: SmartRideViewModel) {
    val route = s.selectedRoute
    if (route.size >= 2) {
        val idx = s.replayIndex.coerceIn(0, route.size - 1)
        RouteMap(
            path = route.map { MapPoint(it.lat, it.lon, it.speedKmh) },
            position = route[idx].let { MapPoint(it.lat, it.lon, it.speedKmh) },
            th = th,
            modifier = Modifier.fillMaxWidth().height(240.dp)
                .border(0.5.dp, th.border.toColor().copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .graphicsLayer { clip = true; shape = RoundedCornerShape(16.dp) },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Route replay", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = th.textMuted.toColor().copy(alpha = 0.7f))
            Text("%.0f km/h".format(route[idx].speedKmh), fontSize = 14.sp, fontWeight = FontWeight.Black, color = speedColor(route[idx].speedKmh))
        }
        Slider(
            value = idx.toFloat(), onValueChange = { vm.setReplayIndex(it.toInt()) },
            valueRange = 0f..(route.size - 1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = th.accent.toColor(), activeTrackColor = th.accent.toColor(),
                inactiveTrackColor = (if (th.isLight) th.border.toColor() else Color.White).copy(alpha = 0.15f)),
        )
        SpeedLegend(th)
        Spacer(Modifier.height(14.dp))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricsNodeGlass("START", Format.time(ride.startEpoch), th, Modifier.weight(1f))
            MetricsNodeGlass("END", Format.time(ride.endEpoch), th, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricsNodeGlass("DISTANCE", "${Format.km(ride.distanceM)} km", th, Modifier.weight(1f))
            MetricsNodeGlass("DURATION", Format.duration(ride.durationS), th, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricsNodeGlass("MAX SPEED", "%.0f km/h".format(ride.maxSpeedKmh), th, Modifier.weight(1f))
            MetricsNodeGlass("AVG SPEED", "%.1f km/h".format(ride.avgSpeedKmh), th, Modifier.weight(1f))
        }
    }
}

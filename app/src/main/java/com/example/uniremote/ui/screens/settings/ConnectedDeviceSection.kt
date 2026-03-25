package com.example.uniremote.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.R
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.ui.theme.*
import com.example.uniremote.viewmodel.ConnectionStatus

// ─────────────────────────────────────────────────────────────────────────────
// ConnectedDeviceSection
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectedDeviceSection(
    status:               ConnectionStatus,
    device:               TvDevice?,
    autoReconnect:        Boolean,
    onAutoReconnectToggle:(Boolean) -> Unit,
    onWakeTV:             () -> Unit,
    onDisconnect:         () -> Unit
) {
    val isOnline    = status is ConnectionStatus.Connected
    val isConnected = status is ConnectionStatus.Connected

    var wolSent by remember { mutableStateOf(false) }
    LaunchedEffect(wolSent) { if (wolSent) { kotlinx.coroutines.delay(3000); wolSent = false } }
    LaunchedEffect(status) { if (status is ConnectionStatus.Connected) wolSent = false }

    val infiniteTransition = rememberInfiniteTransition(label = "wol_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "wol_scale"
    )

    val statusLabel = when (status) {
        is ConnectionStatus.Connected -> stringResource(R.string.settings_online)
        is ConnectionStatus.Offline   -> stringResource(R.string.settings_standby)
        else                          -> stringResource(R.string.settings_offline)
    }
    val statusColor = if (isOnline) primary_fixed_dim else MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_connected_device), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f), letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(4.dp))
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, letterSpacing = 1.5.sp)
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)).background(GlassBtnBg)
                .border(1.dp, if (!isOnline && device != null) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else GlassBtnBorder, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column {
                if (device != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (isOnline) surface_container_highest else MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isOnline) Icons.Filled.Tv else Icons.Filled.WifiOff, "TV",
                                tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp))
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${device.brand.displayName} • ${device.ip}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(surface_container_high), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.WifiOff, "No device", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(36.dp))
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        Text(stringResource(R.string.settings_no_device), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.4f))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Auto-Reconnect toggle
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(0.dp)).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.settings_auto_reconnect_title), style = MaterialTheme.typography.labelMedium, color = Color.White, letterSpacing = 1.sp)
                        Text(stringResource(R.string.settings_auto_reconnect_desc), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = Color.White.copy(alpha = 0.4f))
                    }
                    Box(
                        modifier = Modifier.width(48.dp).height(24.dp).clip(CircleShape)
                            .background(if (autoReconnect) MaterialTheme.colorScheme.primaryContainer else surface_bright)
                            .clickable { onAutoReconnectToggle(!autoReconnect) }.padding(4.dp),
                        contentAlignment = if (autoReconnect) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (autoReconnect) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }

                // Wake-on-LAN
                AnimatedVisibility(visible = !isOnline && device != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (wolSent) stringResource(R.string.settings_wol_waking) else stringResource(R.string.settings_wol_hint),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = if (wolSent) primary_fixed_dim else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().scale(if (wolSent) pulseScale else 1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (wolSent) Brush.linearGradient(listOf(primary_fixed_dim, MaterialTheme.colorScheme.secondary))
                                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.error.copy(alpha = 0.8f), MaterialTheme.colorScheme.error))
                                )
                                .border(1.dp, if (wolSent) primary_fixed_dim.copy(alpha = 0.5f) else MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                .clickable(enabled = !wolSent) { onWakeTV(); wolSent = true }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (wolSent) Icons.Filled.Bolt else Icons.Filled.Bedtime, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (wolSent) stringResource(R.string.settings_waking_up) else stringResource(R.string.settings_wake_tv),
                                    style = MaterialTheme.typography.labelLarge, color = Color.White, letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }

                // Disconnect
                AnimatedVisibility(visible = isConnected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.LinkOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConnectivityBentoGrid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectivityBentoGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Haptic card
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                    .background(surface_container_low).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Vibration, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("TACTILE ENGINE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic Feedback", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                    Box(modifier = Modifier.size(width = 40.dp, height = 20.dp).clip(CircleShape).background(surface_bright), contentAlignment = Alignment.CenterEnd) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("INTENSITY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.5f))
                        Text("85%", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(surface_container_highest)) {
                        Box(modifier = Modifier.fillMaxWidth(0.85f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            // Response card
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                    .background(surface_container_low).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TouchApp, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("RESPONSE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dynamic Scaling", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                    Box(modifier = Modifier.size(width = 40.dp, height = 20.dp).clip(CircleShape).background(surface_bright), contentAlignment = Alignment.CenterStart) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
                    }
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SENSITIVITY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.5f))
                        Text("Medium", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Box(modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Box(modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape).background(surface_container_highest))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ScanButtonSection
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ScanButtonSection(
    isScanning:   Boolean,
    discovered:   List<TvDevice>,
    onScanToggle: () -> Unit,
    onConnect:    (TvDevice) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val radarScale by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "radar"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().scale(if (isScanning) radarScale else 1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)))
                .clickable { onScanToggle() }.padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radar, null, tint = MaterialTheme.colorScheme.surface)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    if (isScanning) stringResource(R.string.settings_scanning_label) else stringResource(R.string.settings_scan_label),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface, letterSpacing = 2.sp
                )
            }
        }
        Text(
            if (isScanning) stringResource(R.string.settings_scanning_hint) else stringResource(R.string.settings_scan_hint),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.4f), letterSpacing = 1.sp
        )
        if (discovered.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_discovered_devices), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f), letterSpacing = 1.5.sp)
                discovered.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)).background(GlassBtnBg)
                            .border(1.dp, GlassBtnBorder, RoundedCornerShape(16.dp))
                            .clickable { onConnect(device) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (device.brand == TvBrand.ANDROID) Icons.Filled.DesktopWindows else Icons.Filled.Tv,
                            device.name, tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                            Text("${device.brand.displayName} • ${device.ip}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ChevronRight, "Connect", tint = Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

package com.example.uniremote.ui.screens

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.theme.*
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Simple in-memory state (replace with ViewModel / DataStore in production)
// ─────────────────────────────────────────────────────────────────────────────
object SettingsState {
    var autoReconnect = mutableStateOf(true)
    var tvOnline      = mutableStateOf(true)   // false = TV is in standby / offline
    var wolSent       = mutableStateOf(false)  // feedback flag after WoL packet
}

@Composable
fun SettingsScreen(
    onNavigate: (NavigationTab) -> Unit
) {
    Scaffold(
        topBar = {
            TopBar(
                title    = "DIGITAL PILOT",
                subtitle = null,
                onPowerClick = {
                    // Toggle TV online status (simulates power toggle)
                    SettingsState.tvOnline.value = !SettingsState.tvOnline.value
                }
            )
        },
        bottomBar = {
            BottomNavBar(currentTab = NavigationTab.SETTINGS, onTabSelected = onNavigate)
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(PremiumBgStart, PremiumBgEnd)))
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                ConnectedDeviceSection()
                ConnectivityBentoGrid()
                ScanButtonSection()
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Connected device card + Auto-Reconnect toggle + Wake-on-LAN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ConnectedDeviceSection() {
    val tvOnline      by SettingsState.tvOnline
    val autoReconnect by SettingsState.autoReconnect
    var wolSent       by SettingsState.wolSent

    // Reset WoL sent flag after 3 seconds
    LaunchedEffect(wolSent) {
        if (wolSent) {
            delay(3000)
            wolSent = false
        }
    }

    // Pulse animation for "waking" state
    val infiniteTransition = rememberInfiniteTransition(label = "wol_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "wol_scale"
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONNECTED DEVICE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (tvOnline) primary_fixed_dim else MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (tvOnline) "ONLINE" else "STANDBY",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tvOnline) primary_fixed_dim else MaterialTheme.colorScheme.error,
                    letterSpacing = 1.5.sp
                )
            }
        }

        // Device card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(GlassBtnBg)
                .border(
                    1.dp,
                    if (!tvOnline) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else GlassBtnBorder,
                    RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {
            Column {
                // TV info row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (tvOnline) surface_container_highest
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (tvOnline) Icons.Filled.Tv else Icons.Filled.WifiOff,
                            contentDescription = "TV",
                            tint = if (tvOnline) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(24.dp))
                    Column {
                        Text(
                            text = "OLED Living Room",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "BRAVIA XR-65A80J • 192.168.1.42",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Auto-Reconnect toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(0.dp)
                        )
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AUTO-RECONNECT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Connect automatically on launch",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Toggle
                    Box(
                        modifier = Modifier
                            .width(48.dp).height(24.dp)
                            .clip(CircleShape)
                            .background(if (autoReconnect) MaterialTheme.colorScheme.primaryContainer else surface_bright)
                            .clickable { SettingsState.autoReconnect.value = !autoReconnect }
                            .padding(4.dp),
                        contentAlignment = if (autoReconnect) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp).clip(CircleShape)
                                .background(
                                    if (autoReconnect) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                    }
                }

                // ── Wake-on-LAN section (visible only when TV is offline) ──
                AnimatedVisibility(
                    visible = !tvOnline,
                    enter = fadeIn() + expandVertically(),
                    exit  = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // WoL status label
                        Text(
                            text = if (wolSent) "✓  MAGIC PACKET SENT – TV IS WAKING UP"
                                   else "TV is in standby. Send a Wake-on-LAN packet to wake it.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = if (wolSent) primary_fixed_dim
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Wake TV button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(if (wolSent) pulseScale else 1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (wolSent)
                                        Brush.linearGradient(listOf(primary_fixed_dim, MaterialTheme.colorScheme.secondary))
                                    else
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                MaterialTheme.colorScheme.error
                                            )
                                        )
                                )
                                .border(
                                    1.dp,
                                    if (wolSent) primary_fixed_dim.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable(enabled = !wolSent) {
                                    // TODO: call WakeOnLanUtil.sendMagicPacket(macAddress, broadcastIp)
                                    wolSent = true
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (wolSent) Icons.Filled.Bolt else Icons.Filled.Bedtime,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (wolSent) "WAKING UP…" else "WAKE TV",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectivityBentoGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Recently Connected
        Column {
            Text(
                text = "RECENTLY CONNECTED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            val recentDevices = listOf(
                Pair(Icons.Filled.LaptopMac, "Master Bedroom TV"),
                Pair(Icons.Filled.DesktopWindows, "Studio Display")
            )
            recentDevices.forEach { (icon, name) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassBtnBg)
                        .border(1.dp, GlassBtnBorder, RoundedCornerShape(16.dp))
                        .clickable { }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Connect", tint = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tactile Engine
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(surface_container_low)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("TACTILE ENGINE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Haptic Feedback", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.size(width = 40.dp, height = 20.dp).clip(CircleShape).background(surface_bright), contentAlignment = Alignment.CenterEnd) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("INTENSITY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("85%", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(surface_container_highest)) {
                        Box(modifier = Modifier.fillMaxWidth(0.85f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }

            // Response
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(surface_container_low)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("RESPONSE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dynamic Scaling", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.size(width = 40.dp, height = 20.dp).clip(CircleShape).background(surface_bright), contentAlignment = Alignment.CenterStart) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SENSITIVITY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun ScanButtonSection() {
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(3000)
            isScanning = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val radarScale by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "radar"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(if (isScanning) radarScale else 1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                )
                .clickable { isScanning = !isScanning }
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.surface)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isScanning) "SCANNING…" else "SCAN FOR NEW DEVICES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.surface,
                    letterSpacing = 2.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isScanning) "SEARCHING FOR DEVICES ON \"DIGITAL_PILOT_5G\"…"
                   else "TAP TO DISCOVER DEVICES ON YOUR NETWORK",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
    }
}

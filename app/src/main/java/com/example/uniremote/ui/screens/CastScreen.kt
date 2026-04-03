package com.example.uniremote.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.R
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.theme.*
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun CastScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    val isMirroring    by vm.isMirroring.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopBar(title = stringResource(R.string.cast_screen_title), onPowerClick = { vm.power() })
        },
        bottomBar = {
            BottomNavBar(currentTab = NavigationTab.CAST, onTabSelected = onNavigate)
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
                CastingSection()
                MirroringSection(
                    isMirroring  = isMirroring,
                    deviceName   = connectedDevice?.name ?: stringResource(R.string.cast_default_device),
                    onToggle     = { vm.toggleMirroring() }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Media Casting section
// ─────────────────────────────────────────────────────────────────────────────
data class MediaItem(val label: String, val icon: ImageVector, val tab: String)

private val mediaItems = mapOf(
    "Photos" to listOf(
        MediaItem("Vacation 2025", Icons.Filled.Image,      "Photos"),
        MediaItem("Gallery",       Icons.Filled.AddToPhotos, "Photos"),
    ),
    "Videos" to listOf(
        MediaItem("Movie Clip",  Icons.Filled.PlayCircle, "Videos"),
        MediaItem("Browse All",  Icons.Filled.VideoFile,  "Videos"),
    ),
    "Music"  to listOf(
        MediaItem("My Playlist", Icons.Filled.MusicNote,  "Music"),
        MediaItem("Browse All",  Icons.Filled.AddToPhotos, "Music"),
    )
)

@Composable
fun CastingSection() {
    val haptic = LocalHapticFeedback.current
    var selectedTabIndex by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tabs = listOf("Photos", "Videos", "Music")
    val currentTab = tabs[selectedTabIndex]
    var castingItem by remember { mutableStateOf<String?>(null) }

    // Auto-clear casting feedback after 3s
    // NOTE: When Cast API is integrated, replace this with actual cast session state
    LaunchedEffect(castingItem) {
        if (castingItem != null) {
            kotlinx.coroutines.delay(3000)
            castingItem = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = stringResource(R.string.cast_media_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (castingItem != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cast_now_casting),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.cast_gallery_stream),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp
                )
            }
        }

        if (castingItem != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    // TODO: replace "TV" with actual connected device name when Cast API is integrated
                    text = stringResource(R.string.cast_casting_to, castingItem ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassBtnBg)
                .border(1.dp, GlassBtnBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTabIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) surface_bright else Color.Transparent)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTabIndex = index
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Media Preview Grid
        val items = mediaItems[currentTab] ?: emptyList()
        Row(
            modifier = Modifier.fillMaxWidth().height(264.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.getOrNull(0)?.let { item ->
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(surface_container_highest)
                        .border(
                            1.dp,
                            if (castingItem == item.label) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            castingItem = item.label
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)))
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                    Row(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                    if (castingItem == item.label) {
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Tv, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.getOrNull(1)?.let { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(surface_container_highest)
                            .border(
                                1.dp,
                                if (castingItem == item.label) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                castingItem = item.label
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)))
                        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(surface_container_high)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.AddToPhotos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.cast_browse_all), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Screen Mirroring section – wired to ViewModel
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MirroringSection(
    isMirroring: Boolean,
    deviceName: String,          // Fix: use actual connected device name, not hardcoded string
    onToggle: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isLowLatency by remember { mutableStateOf(false) }

    // Fix: Run mirror-pulse animation ONLY when mirroring is active — saves CPU/battery when idle
    val mirrorScale   by if (isMirroring) {
        rememberInfiniteTransition(label = "mirror_pulse")
            .animateFloat(
                initialValue  = 0.98f, targetValue = 1.02f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "mirror_scale"
            )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val signalAlpha   by if (isMirroring) {
        rememberInfiniteTransition(label = "signal_alpha")
            .animateFloat(
                initialValue  = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
                label = "signal_alpha"
            )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(text = stringResource(R.string.cast_mirroring_title), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMirroring) stringResource(R.string.cast_streaming) else stringResource(R.string.cast_ready),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(mirrorScale)
                .clip(RoundedCornerShape(32.dp))
                .background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else surface_container_low)
                .border(1.dp, if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(32.dp))
                .padding(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Visual: Phone → TV
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(64.dp, 112.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else surface_container_lowest)
                            .border(2.dp, if (isMirroring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Smartphone, null, tint = if (isMirroring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }

                    Box(
                        modifier = Modifier.weight(1f).height(2.dp)
                            .background(Brush.horizontalGradient(listOf(
                                MaterialTheme.colorScheme.outlineVariant,
                                if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha) else MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.outlineVariant
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SyncAlt, null,
                            tint = if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else surface_container_low).padding(horizontal = 8.dp)
                        )
                    }

                    Box(
                        modifier = Modifier.size(96.dp, 64.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else surface_container_lowest)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // Start / Stop button — calls vm.toggleMirroring() via onToggle
                // toggleMirroring() shows a "Coming Soon" toast until Cast API is connected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(
                            if (isMirroring)
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.8f)))
                            else
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer))
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle()
                        }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            if (isMirroring) Icons.Filled.Stop else Icons.Filled.Smartphone, null,
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isMirroring) stringResource(R.string.cast_stop_mirroring) else stringResource(R.string.cast_start_mirroring),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                // Low Latency toggle
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(surface_container_highest.copy(alpha = 0.4f)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(surface_bright).padding(8.dp)) {
                            Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.cast_low_latency_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 2.dp))
                            Text(stringResource(R.string.cast_low_latency_desc), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(
                        modifier = Modifier.width(48.dp).height(24.dp).clip(CircleShape)
                            .background(if (isLowLatency) MaterialTheme.colorScheme.primaryContainer else surface_bright)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isLowLatency = !isLowLatency
                            }.padding(4.dp),
                        contentAlignment = if (isLowLatency) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape)
                            .background(if (isLowLatency) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }

                if (isMirroring) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = primary_fixed_dim, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            // Fix: use real connected device name instead of hardcoded "Living Room TV"
                            stringResource(
                                R.string.cast_mirroring_status,
                                deviceName,
                                if (isLowLatency) stringResource(R.string.cast_low_latency_mode) else stringResource(R.string.cast_standard_mode)
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = primary_fixed_dim
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.cast_wifi_hint),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

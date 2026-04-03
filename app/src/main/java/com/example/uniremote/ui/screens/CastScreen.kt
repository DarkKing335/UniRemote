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
    val isMirroring by vm.isMirroring.collectAsStateWithLifecycle()
    val isCastFeatureAvailable by vm.isCastFeatureAvailable.collectAsStateWithLifecycle()
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
                CastingSection(
                    isFeatureAvailable = isCastFeatureAvailable,
                    onLearnMore = { vm.requestCastFeatureInfo() }
                )
                MirroringSection(
                    isMirroring = isMirroring,
                    isFeatureAvailable = isCastFeatureAvailable,
                    hasConnectedDevice = connectedDevice != null,
                    deviceName = connectedDevice?.name ?: stringResource(R.string.cast_default_device),
                    onToggle = { vm.toggleMirroring() },
                    onLearnMore = { vm.requestCastFeatureInfo() }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun CastingSection(
    isFeatureAvailable: Boolean,
    onLearnMore: () -> Unit
) {
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
            Text(
                text = if (isFeatureAvailable) stringResource(R.string.cast_ready) else stringResource(R.string.cast_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(surface_container_low)
                .border(1.dp, GlassBtnBorder, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.cast_unavailable),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.cast_unavailable_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onLearnMore) {
                    Text(stringResource(R.string.cast_unavailable_cta))
                }
            }
        }
    }
}

@Composable
fun MirroringSection(
    isMirroring: Boolean,
    isFeatureAvailable: Boolean,
    hasConnectedDevice: Boolean,
    deviceName: String,
    onToggle: () -> Unit,
    onLearnMore: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isMirroringActive = isFeatureAvailable && isMirroring

    val mirrorScale by if (isMirroringActive) {
        rememberInfiniteTransition(label = "mirror_pulse")
            .animateFloat(
                initialValue  = 0.98f, targetValue = 1.02f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "mirror_scale"
            )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val signalAlpha by if (isMirroringActive) {
        rememberInfiniteTransition(label = "signal_alpha")
            .animateFloat(
                initialValue  = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
                label = "signal_alpha"
            )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    val actionEnabled = isFeatureAvailable && hasConnectedDevice

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
                    .background(
                        when {
                            isMirroringActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            isFeatureAvailable -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(
                            when {
                                isMirroringActive -> MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha)
                                isFeatureAvailable -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isMirroringActive -> stringResource(R.string.cast_streaming)
                        isFeatureAvailable -> stringResource(R.string.cast_ready)
                        else -> stringResource(R.string.cast_unavailable)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isFeatureAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(mirrorScale)
                .clip(RoundedCornerShape(32.dp))
                .background(if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else surface_container_low)
                .border(1.dp, if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(32.dp))
                .padding(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(64.dp, 112.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else surface_container_lowest)
                            .border(2.dp, if (isMirroringActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Smartphone, null, tint = if (isMirroringActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }

                    Box(
                        modifier = Modifier.weight(1f).height(2.dp)
                            .background(Brush.horizontalGradient(listOf(
                                MaterialTheme.colorScheme.outlineVariant,
                                if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha) else MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.outlineVariant
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SyncAlt, null,
                            tint = if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else surface_container_low).padding(horizontal = 8.dp)
                        )
                    }

                    Box(
                        modifier = Modifier.size(96.dp, 64.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isMirroringActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else surface_container_lowest)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(if (actionEnabled) {
                            if (isMirroringActive) {
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.8f)))
                            } else {
                                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer))
                            }
                        } else {
                            Brush.linearGradient(listOf(surface_bright, surface_container_high))
                        })
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (actionEnabled) onToggle() else onLearnMore()
                        }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            if (isMirroringActive) Icons.Filled.Stop else Icons.Filled.Smartphone, null,
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                !isFeatureAvailable -> stringResource(R.string.cast_unavailable)
                                !hasConnectedDevice -> stringResource(R.string.cast_requires_connection)
                                isMirroringActive -> stringResource(R.string.cast_stop_mirroring)
                                else -> stringResource(R.string.cast_start_mirroring)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                if (isMirroringActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = primary_fixed_dim, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(
                                R.string.cast_mirroring_status,
                                deviceName,
                                stringResource(R.string.cast_standard_mode)
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = primary_fixed_dim
                        )
                    }
                } else if (!isFeatureAvailable) {
                    Text(
                        text = stringResource(R.string.cast_unavailable_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

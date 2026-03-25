package com.example.uniremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.uniremote.R
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.MultitaskingBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.screens.remote.DPadTouchpadLayout
import com.example.uniremote.ui.screens.remote.MouseCursorLayout
import com.example.uniremote.ui.screens.remote.KeyboardLayout
import com.example.uniremote.ui.screens.remote.InputSourceLayout
import com.example.uniremote.ui.screens.remote.MainLayout
import com.example.uniremote.ui.screens.remote.MediaPlaybackLayout
import com.example.uniremote.ui.screens.remote.NumpadLayout
import com.example.uniremote.ui.theme.PremiumBgEnd
import com.example.uniremote.ui.theme.PremiumBgStart
import com.example.uniremote.viewmodel.ConnectionStatus
import com.example.uniremote.viewmodel.RemoteViewModel
import kotlin.math.abs

// ── Top-level tab definition ────────────────────────────────────────────────
private data class RemoteTab(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true   // tab 4 = disabled
)

private val remoteTabs = listOf(
    RemoteTab(Icons.Filled.GridView,  "Remote"),
    RemoteTab(Icons.Filled.Games,     "D-Pad"),
    RemoteTab(Icons.Filled.NearMe,    "TouchPad"),
    RemoteTab(Icons.Filled.Keyboard,  "Keyboard")   // guard inside KeyboardLayout
)

// ── Screen ──────────────────────────────────────────────────────────────────
@Composable
fun MainRemoteScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    var selectedRemoteTab by rememberSaveable { mutableStateOf(0) }
    val status by vm.connectionStatus.collectAsState()
    val connectedDevice by vm.connectedDevice.collectAsState()
    val activeDevices by vm.activeDevices.collectAsState()
    val isBroadcast by vm.isBroadcastMode.collectAsState()
    val isConnecting = status is ConnectionStatus.Connecting

    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.main_remote_title), onPowerClick = { vm.power() }) },
        bottomBar = { BottomNavBar(currentTab = NavigationTab.REMOTE, onTabSelected = onNavigate) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(PremiumBgStart, PremiumBgEnd)))
                .padding(paddingValues)
        ) {
            // ── Top Tab Bar ────────────────────────────────────────────────
            RemoteTopTabBar(
                tabs = remoteTabs,
                selectedIndex = selectedRemoteTab,
                onTabSelected = { index ->
                    if (remoteTabs[index].enabled) selectedRemoteTab = index
                    // disabled tab: ignore click, state unchanged
                }
            )

            // ── Multitasking Bar ──────────────────────────────────────────
            if (activeDevices.size > 1 || isBroadcast) {
                MultitaskingBar(
                    activeDevices = activeDevices,
                    selectedDevice = connectedDevice,
                    isBroadcast = isBroadcast,
                    onDeviceClick = { vm.selectDevice(it) },
                    onBroadcastToggle = { vm.toggleBroadcastMode() }
                )
            }

            // ── Content ───────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isBroadcast,
                        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF6366F1).copy(alpha = 0.8f))
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = "Broadcast",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Broadcast Mode Active",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }

                    androidx.compose.animation.Crossfade(
                        targetState = selectedRemoteTab,
                        label = "remoteTabTransition",
                        modifier = Modifier.weight(1f)
                    ) { tab ->
                        Box(Modifier.fillMaxSize()) {
                            when (tab) {
                                0 -> RemoteVerticalPagerContent(vm)
                                1 -> DPadTouchpadLayout(vm)
                                2 -> MouseCursorLayout(vm)
                                3 -> KeyboardLayout(vm)
                            }
                        }
                    }
                }

                if (isConnecting) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.38f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.connecting_overlay_message),
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Top tab bar composable ──────────────────────────────────────────────────
@Composable
private fun RemoteTopTabBar(
    tabs: List<RemoteTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.25f)),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedIndex == index
            val iconTint = when {
                !tab.enabled  -> Color.White.copy(alpha = 0.25f)
                isSelected    -> Color.White
                else          -> Color.White.copy(alpha = 0.50f)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTabSelected(index) }
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Active underline
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .fillMaxWidth(0.5f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                )
            }
        }
    }
}

// ── Tab 1 content: VerticalPager with 4 pages ───────────────────────────────
@Composable
private fun RemoteVerticalPagerContent(vm: RemoteViewModel) {
    val pagerState = rememberPagerState(pageCount = { 4 })

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val pageOffset = abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
            val pageAlpha = (1f - pageOffset).coerceIn(0f, 1f)

            when (page) {
                0 -> MainLayout(pageAlpha, vm)
                1 -> NumpadLayout(pageAlpha, vm)
                2 -> MediaPlaybackLayout(vm)
                3 -> InputSourceLayout(vm)
            }
        }

        // Vertical dot indicator
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(4) { i ->
                val isSelected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.3f))
                )
            }
        }
    }
}



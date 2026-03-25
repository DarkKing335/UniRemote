package com.example.uniremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.uniremote.R
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.screens.settings.ConnectedDeviceSection
import com.example.uniremote.ui.screens.settings.ConnectivityBentoGrid
import com.example.uniremote.ui.screens.settings.KnownDevicesSection
import com.example.uniremote.ui.screens.settings.MacroManagerCard
import com.example.uniremote.ui.screens.settings.ScanButtonSection
import com.example.uniremote.ui.theme.PremiumBgEnd
import com.example.uniremote.ui.theme.PremiumBgStart
import com.example.uniremote.viewmodel.RemoteViewModel

// ─────────────────────────────────────────────────────────────────────────────
// SettingsScreen  (entry point – delegates to sub-composables)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    val status          by vm.connectionStatus.collectAsState()
    val connectedDevice by vm.connectedDevice.collectAsState()
    val autoReconnect   by vm.autoReconnect.collectAsState(initial = true)
    val discovered      by vm.discoveredDevices.collectAsState()
    val knownDevices    by vm.knownDevices.collectAsState()
    val currentSsid     by vm.currentSsid.collectAsState()
    val userMacros      by vm.userMacros.collectAsState()
    var isScanning      by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Scaffold(
        topBar = {
            TopBar(
                title        = stringResource(R.string.main_remote_title),
                subtitle     = null,
                onPowerClick = { vm.power() }
            )
        },
        bottomBar   = { BottomNavBar(currentTab = NavigationTab.SETTINGS, onTabSelected = onNavigate) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(PremiumBgStart, PremiumBgEnd)))
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                ConnectedDeviceSection(
                    status               = status,
                    device               = connectedDevice,
                    autoReconnect        = autoReconnect,
                    onAutoReconnectToggle = { vm.setAutoReconnect(it) },
                    onWakeTV             = { vm.wakeTV() },
                    onDisconnect         = { vm.disconnect() }
                )
                ConnectivityBentoGrid()
                ScanButtonSection(
                    isScanning   = isScanning,
                    discovered   = discovered,
                    onScanToggle = {
                        isScanning = !isScanning
                        if (isScanning) vm.scanDevices() else vm.stopScan()
                    },
                    onConnect    = { device ->
                        isScanning = false
                        vm.stopScan()
                        vm.connectTo(device)
                    }
                )
                KnownDevicesSection(
                    knownDevices    = knownDevices,
                    connectedDevice = connectedDevice,
                    currentSsid     = currentSsid,
                    onConnect       = { vm.connectTo(it) },
                    onForget        = { vm.forgetDevice(it) }
                )
                MacroManagerCard(
                    macros   = userMacros,
                    onSave   = { vm.saveMacro(it) },
                    onDelete = { vm.deleteMacro(it) },
                    onRun    = { vm.runUserMacro(it) }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

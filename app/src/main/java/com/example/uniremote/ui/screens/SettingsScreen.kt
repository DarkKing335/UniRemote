package com.example.uniremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.uniremote.R
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.screens.settings.ConnectedDeviceSection
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
    val status          by vm.connectionStatus.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()
    val autoReconnect   by vm.autoReconnect.collectAsStateWithLifecycle(initialValue = true)
    val discovered      by vm.discoveredDevices.collectAsStateWithLifecycle()
    val knownDevices    by vm.knownDevices.collectAsStateWithLifecycle()
    val currentSsid     by vm.currentSsid.collectAsStateWithLifecycle()
    val userMacros      by vm.userMacros.collectAsStateWithLifecycle()
    val isMacroRunning  by vm.isMacroRunning.collectAsStateWithLifecycle()
    val currentMacroId  by vm.currentMacroId.collectAsStateWithLifecycle()
    var isScanning      by remember { mutableStateOf(false) }
    val context         = LocalContext.current

    val scrollState     = rememberScrollState()
    val coroutineScope  = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.toastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Fix: also reset isScanning so UI is consistent when user navigates away and returns
    DisposableEffect(Unit) {
        onDispose {
            vm.stopScan()
            isScanning = false
        }
    }

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
                    .verticalScroll(scrollState)
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
                ScanButtonSection(
                    isScanning   = isScanning,
                    discovered   = discovered,
                    onScanToggle = {
                        isScanning = !isScanning
                        if (isScanning) vm.scanDevices() else vm.stopScan()
                    },
                    onConnect = { device ->
                        isScanning = false
                        vm.stopScan()
                        // Fix: business logic moved to ViewModel.connectOrPair()
                        // UI no longer needs to know about TvBrand or pairing rules
                        vm.connectOrPair(device)
                        coroutineScope.launch { scrollState.animateScrollTo(0) }
                    }
                )
                KnownDevicesSection(
                    knownDevices    = knownDevices,
                    connectedDevice = connectedDevice,
                    currentSsid     = currentSsid,
                    onConnect       = {
                        // Use connectOrPair (not connectTo) so Sony/Android TV devices
                        // that haven't been paired yet still go through the PIN flow.
                        vm.connectOrPair(it)
                        coroutineScope.launch { scrollState.animateScrollTo(0) }
                    },
                    onForget = { vm.forgetDevice(it) }
                )
                MacroManagerCard(
                    macros          = userMacros,
                    isMacroRunning  = isMacroRunning,
                    runningMacroId  = currentMacroId,
                    onSave          = { vm.saveMacro(it) },
                    onDelete        = { vm.deleteMacro(it) },
                    onRun           = { vm.runUserMacro(it) }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

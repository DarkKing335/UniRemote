package com.example.uniremote.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.uniremote.network.PairingState
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.screens.AppsScreen
import com.example.uniremote.ui.screens.CastScreen
import com.example.uniremote.ui.screens.MainRemoteScreen
import com.example.uniremote.ui.screens.SettingsScreen
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun AppNavigation() {
    var currentScreen by rememberSaveable { mutableStateOf(NavigationTab.REMOTE) }
    // Single ViewModel shared across all screens
    val vm: RemoteViewModel = viewModel()
    val pairingState by vm.pairingState.collectAsStateWithLifecycle()

    // ── Global PIN dialog ──────────────────────────────────────────────────────
    // Rendered at root level so it appears over ANY tab, not just SettingsScreen.
    // This was the primary cause of the "connected but can't control" bug:
    // if the user was on the Remote tab, the dialog never showed, PIN timed out,
    // the app fell back to ADB, which silently rejected all shell commands.
    GlobalPairingDialog(
        pairingState = pairingState,
        onSubmitPin  = { vm.submitPairingPin(it) },
        onCancel     = { vm.cancelPairing() }
    )

    when (currentScreen) {
        NavigationTab.REMOTE   -> MainRemoteScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.APPS     -> AppsScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.CAST     -> CastScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.SETTINGS -> SettingsScreen(vm = vm, onNavigate = { currentScreen = it })
    }
}

// ── Shared pairing dialog — used at global level ───────────────────────────────
/**
 * Shows a modal dialog when the Google TV pairing handshake requires user input.
 *
 * States handled:
 *  - CONNECTING       → spinner + "Connecting..." message
 *  - WAITING_FOR_PIN  → 6-char PIN input field + Confirm button
 *
 * The dialog is dismissed (and pairing cancelled) on back-press / outside tap.
 */
@Composable
fun GlobalPairingDialog(
    pairingState: PairingState,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit
) {
    if (pairingState != PairingState.WAITING_FOR_PIN && pairingState != PairingState.CONNECTING) return

    var pinCode by remember { mutableStateOf("") }

    // Reset PIN field each time the dialog is freshly shown
    LaunchedEffect(pairingState) {
        if (pairingState == PairingState.CONNECTING) pinCode = ""
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (pairingState == PairingState.CONNECTING) "Dang ket noi..."
                else "Yeu cau ma PIN"
            )
        },
        text = {
            if (pairingState == PairingState.CONNECTING) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                Column {
                    Text(
                        "Vui long nhap ma 6 ky tu dang hien thi tren man hinh TV.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = {
                            if (it.length <= 6) pinCode = it.filter { char ->
                                char.isDigit() || char in 'A'..'Z' || char in 'a'..'z'
                            }
                        },
                        label = { Text("Nhap PIN (6 ky tu)") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            if (pairingState == PairingState.WAITING_FOR_PIN) {
                Button(
                    onClick = { onSubmitPin(pinCode) },
                    enabled = pinCode.length == 6
                ) { Text("Ghep noi") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Huy") }
        }
    )
}

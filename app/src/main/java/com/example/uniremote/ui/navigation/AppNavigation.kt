package com.example.uniremote.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.uniremote.R
import com.example.uniremote.network.PairingState
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.screens.AppsScreen
import com.example.uniremote.ui.screens.CastScreen
import com.example.uniremote.ui.screens.MainRemoteScreen
import com.example.uniremote.ui.screens.SettingsScreen
import com.example.uniremote.viewmodel.RemoteViewModel
import kotlinx.coroutines.flow.Flow

@Composable
fun AppNavigation(
    vm: RemoteViewModel = viewModel(),
    externalNavigationEvents: Flow<NavigationTab>? = null
) {
    var currentScreen by rememberSaveable { mutableStateOf(NavigationTab.REMOTE) }
    val pairingState by vm.pairingState.collectAsStateWithLifecycle()
    val connectedDevice by vm.connectedDevice.collectAsStateWithLifecycle()

    LaunchedEffect(externalNavigationEvents) {
        externalNavigationEvents?.collect { target ->
            currentScreen = target
        }
    }

    // Global PIN dialog — rendered at root level so it appears over ANY tab
    GlobalPairingDialog(
        pairingState = pairingState,
        connectedDevice = connectedDevice,
        onSubmitPin  = { vm.submitPairingPin(it.uppercase()) },
        onCancel     = { vm.cancelPairing() }
    )

    when (currentScreen) {
        NavigationTab.REMOTE   -> MainRemoteScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.APPS     -> AppsScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.CAST     -> CastScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.SETTINGS -> SettingsScreen(vm = vm, onNavigate = { currentScreen = it })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pairing dialog — styled to match reference remote apps
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlobalPairingDialog(
    pairingState: PairingState,
    connectedDevice: com.example.uniremote.data.TvDevice?,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit
) {
    if (pairingState != PairingState.WAITING_FOR_PIN &&
        pairingState != PairingState.CONNECTING) return

    var pinCode by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(pairingState) {
        if (pairingState == PairingState.CONNECTING) pinCode = ""
    }

    val isLg = connectedDevice?.brand == com.example.uniremote.data.TvBrand.LG
    val expectedPinLength = if (isLg) 8 else 6
    // LG PINs can be up to 8 characters, Google TV is 6.
    val boxWidth = if (expectedPinLength == 8) 32.dp else 44.dp
    val spacing = if (expectedPinLength == 8) 4.dp else 8.dp

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // ── TV icon ────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Title ──────────────────────────────────────────────────
                Text(
                    text = stringResource(R.string.pairing_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                // ── Subtitle ───────────────────────────────────────────────
                Text(
                    text = if (pairingState == PairingState.CONNECTING)
                        stringResource(R.string.pairing_connecting)
                    else
                        stringResource(R.string.pairing_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(28.dp))

                // ── Spinner (CONNECTING) or OTP boxes (WAITING_FOR_PIN) ───
                if (pairingState == PairingState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    LaunchedEffect(Unit) {
                        delay(200) // Give UI a moment to layout
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }

                    // Hidden full-width text field that captures keyboard input
                    BasicTextField(
                        value = pinCode,
                        onValueChange = { raw ->
                            val filtered = raw
                                .filter { it.isLetterOrDigit() }
                                .uppercase()
                                .take(expectedPinLength)
                            pinCode = filtered
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (pinCode.length == expectedPinLength) onSubmitPin(pinCode) }
                        ),
                        cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .size(1.dp), // invisible but focusable
                        decorationBox = { it() }
                    )

                    // ── Dynamic OTP boxes ──────────────────────────────────
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(expectedPinLength) { index ->
                            val char = pinCode.getOrNull(index)
                            val isCurrent = index == pinCode.length && pinCode.length < expectedPinLength

                            Box(
                                modifier = Modifier
                                    .size(width = boxWidth, height = 52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (char != null)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = if (isCurrent) 2.dp else 1.dp,
                                        color = if (isCurrent)
                                            MaterialTheme.colorScheme.primary
                                        else if (char != null)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else
                                            MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (char != null) {
                                    Text(
                                        text = char.toString(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    // Dash placeholder
                                    Text(
                                        text = "–",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pairing_char_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ── Buttons ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.pairing_cancel)) }

                    if (pairingState == PairingState.WAITING_FOR_PIN) {
                        Button(
                            onClick = { onSubmitPin(pinCode) },
                            enabled = pinCode.length == expectedPinLength,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.pairing_confirm)) }
                    }
                }
            }
        }
    }
}

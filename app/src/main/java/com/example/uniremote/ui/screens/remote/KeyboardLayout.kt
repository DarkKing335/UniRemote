package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.R
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun KeyboardLayout(vm: RemoteViewModel) {
    val haptic = LocalHapticFeedback.current
    var inputText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    val status            by vm.connectionStatus.collectAsStateWithLifecycle()
    val isTextInputActive by vm.isTextInputActive.collectAsStateWithLifecycle()
    val isConnected       = status is ConnectionStatus.Connected
    val focusRequester    = remember { FocusRequester() }

    // UX Fix: When user switches to the Keyboard tab, auto-activate text input so
    // the cursor immediately appears — eliminates the non-discoverable manual toggle.
    // Auto-deactivate when navigating away so the indicator is reset.
    DisposableEffect(Unit) {
        vm.setTextInputActive(true)
        onDispose {
            vm.setTextInputActive(false)
        }
    }

    // Auto-focus the text field when text input becomes active
    LaunchedEffect(isTextInputActive) {
        if (isTextInputActive && isConnected) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // ── Error dialog: TV không có ô nhập văn bản ─────────────────────────
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor   = Color(0xFFEFEFEF),
            shape            = RoundedCornerShape(4.dp),
            text = {
                Text(
                    text       = stringResource(R.string.keyboard_send_error_message),
                    color      = Color(0xFF232323),
                    fontSize   = 17.sp,
                    lineHeight = 26.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.ok), color = Color(0xFF00897B), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // ── Layout ──────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement   = Arrangement.spacedBy(20.dp),
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {

        // ── Status chip (manual override toggle) ─────────────────────────
        // The chip is now informational — text input auto-activates on entry.
        // User can still tap it to manually toggle if TV doesn't show a field.
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (isTextInputActive) Color(0xFF00897B).copy(alpha = 0.18f)
                    else Color.White.copy(alpha = 0.06f)
                )
                .border(
                    1.dp,
                    if (isTextInputActive) Color(0xFF00897B).copy(alpha = 0.5f)
                    else Color.White.copy(alpha = 0.15f),
                    CircleShape
                )
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    vm.setTextInputActive(!isTextInputActive)
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector        = if (isTextInputActive) Icons.Filled.Keyboard else Icons.Filled.KeyboardHide,
                contentDescription = null,
                tint               = if (isTextInputActive) Color(0xFF00897B) else Color.White.copy(alpha = 0.4f),
                modifier           = Modifier.size(18.dp)
            )
            Text(
                text          = if (isTextInputActive) stringResource(R.string.keyboard_active_hint)
                                else stringResource(R.string.keyboard_inactive_hint),
                fontSize      = 12.sp,
                color         = if (isTextInputActive) Color(0xFF00897B) else Color.White.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        // ── Text field + X button ────────────────────────────────────────
        OutlinedTextField(
            value         = inputText,
            onValueChange = { inputText = it },
            modifier      = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .focusRequester(focusRequester),
            singleLine    = true,
            enabled       = isConnected && isTextInputActive,
            textStyle     = LocalTextStyle.current.copy(color = Color.White),
            placeholder   = {
                Text(
                    if (!isConnected) stringResource(R.string.keyboard_placeholder_disconnected)
                    else if (!isTextInputActive) stringResource(R.string.keyboard_placeholder_waiting)
                    else stringResource(R.string.keyboard_placeholder_ready),
                    color = Color.White.copy(alpha = 0.3f)
                )
            },
            trailingIcon = {
                if (inputText.isNotEmpty()) {
                    IconButton(onClick = { inputText = "" }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_text_content_description),
                            tint = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Color.White.copy(alpha = 0.25f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                disabledBorderColor  = Color.White.copy(alpha = 0.08f),
                cursorColor          = Color.White
            )
        )

        // ── Nút Gửi ─────────────────────────────────────────────────────
        Button(
            onClick = {
                when {
                    !isConnected       -> showDialog = true
                    !isTextInputActive -> showDialog = true
                    inputText.isBlank() -> { /* ignore empty */ }
                    else -> {
                        vm.sendTextAndEnter(inputText)
                        inputText = ""
                    }
                }
            },
            modifier = Modifier.width(260.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected && isTextInputActive) Color(0xFF00897B) else Color(0xFFA9A9A9),
                contentColor   = Color.White
            ),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(stringResource(R.string.send), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

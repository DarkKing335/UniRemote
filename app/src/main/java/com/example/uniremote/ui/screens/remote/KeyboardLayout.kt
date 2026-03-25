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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.R
import com.example.uniremote.viewmodel.ConnectionStatus
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun KeyboardLayout(vm: RemoteViewModel? = null) {
    var inputText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    val status           by (vm?.connectionStatus ?: return).collectAsState()
    val isTextInputActive by vm.isTextInputActive.collectAsState()
    val isConnected       = status is ConnectionStatus.Connected

    // ── Error dialog: TV không có ô nhập văn bản ─────────────────────────
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor   = Color(0xFFEFEFEF),
            shape            = RoundedCornerShape(4.dp),
            text = {
                Text(
                    text      = stringResource(R.string.keyboard_send_error_message),
                    color     = Color(0xFF232323),
                    fontSize  = 17.sp,
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

        // ── Status chip: TV có ô nhập hay không ─────────────────────────
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
                .clickable { vm.setTextInputActive(!isTextInputActive) }
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
                text      = if (isTextInputActive) "TV đang hiển thị ô nhập" else "Bấm khi TV hiện ô nhập văn bản",
                fontSize  = 12.sp,
                color     = if (isTextInputActive) Color(0xFF00897B) else Color.White.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        // ── Text field + X button ────────────────────────────────────────
        OutlinedTextField(
            value         = inputText,
            onValueChange = { inputText = it },
            modifier      = Modifier.fillMaxWidth().height(58.dp),
            singleLine    = true,
            enabled       = isConnected && isTextInputActive,
            textStyle     = LocalTextStyle.current.copy(color = Color.White),
            placeholder   = {
                Text(
                    if (!isConnected) "Chưa kết nối TV"
                    else if (!isTextInputActive) "Chờ TV mở ô nhập…"
                    else "Nhập văn bản…",
                    color = Color.White.copy(alpha = 0.3f)
                )
            },
            trailingIcon = {
                if (inputText.isNotEmpty()) {
                    IconButton(onClick = { inputText = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_text_content_description), tint = Color.White.copy(alpha = 0.4f))
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
                    !isConnected         -> showDialog = true
                    !isTextInputActive   -> showDialog = true
                    inputText.isBlank()  -> { /* ignore empty */ }
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

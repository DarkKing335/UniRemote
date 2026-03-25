package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val status by (vm?.connectionStatus ?: return).collectAsState()
    val isConnected = status is ConnectionStatus.Connected

    // ── Dialog: TV không hiển thị màn hình nhập ─────────────────────────
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xFFEFEFEF),
            shape = RoundedCornerShape(4.dp),
            text = {
                Text(
                    text = stringResource(R.string.keyboard_send_error_message),
                    color = Color(0xFF232323),
                    fontSize = 17.sp,
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Text field + X button
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = Color.White),
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
                cursorColor          = Color.White
            )
        )

        // Nút Gửi
        Button(
            onClick = {
                if (isConnected && inputText.isNotBlank()) {
                    vm.sendTextAndEnter(inputText)
                    inputText = ""
                } else {
                    showDialog = true
                }
            },
            modifier = Modifier
                .width(260.dp)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFA9A9A9),
                contentColor   = Color(0xFF2A2A2A)
            ),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(stringResource(R.string.send), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

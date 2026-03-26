package com.example.uniremote.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// KnownDevicesSection
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun KnownDevicesSection(
    knownDevices:    List<TvDevice>,
    connectedDevice: TvDevice?,
    currentSsid:     String?,
    onConnect:       (TvDevice) -> Unit,
    onForget:        (String) -> Unit
) {
    var forgetTarget by remember { mutableStateOf<TvDevice?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    "THIẾT BỊ ĐÃ LƯU",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text("Known Devices", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            }
            // WiFi SSID chip
            if (currentSsid != null) {
                Row(
                    modifier = Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(currentSsid, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary, letterSpacing = 0.5.sp)
                }
            }
        }

        // Device list or empty state
        if (knownDevices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(GlassBtnBg).border(1.dp, GlassBtnBorder, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.DevicesOther, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
                    Text("Chưa có thiết bị nào được kết nối", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.4f))
                    Text("Quét và kết nối thiết bị để lưu tại đây", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.25f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                knownDevices.forEach { device ->
                    KnownDeviceRow(
                        device      = device,
                        isConnected = device.id == connectedDevice?.id,
                        onConnect   = { onConnect(device) },
                        onForget    = { forgetTarget = device }
                    )
                }
            }
        }
    }

    // Forget confirmation dialog
    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            containerColor   = Color(0xFF1A2029),
            title  = { Text("Xóa thiết bị?", color = Color.White) },
            text   = { Text("\"${target.name}\" sẽ bị xóa khỏi danh sách lưu và không được tự kết nối lại.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = { onForget(target.id); forgetTarget = null }) {
                    Text("Xóa", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) {
                    Text("Hủy", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// KnownDeviceRow  (private to this feature)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun KnownDeviceRow(
    device:      TvDevice,
    isConnected: Boolean,
    onConnect:   () -> Unit,
    onForget:    () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else GlassBtnBg)
            .border(1.dp, if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else GlassBtnBorder, RoundedCornerShape(18.dp))
            .clickable { onConnect() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (device.brand == TvBrand.ANDROID) Icons.Filled.DesktopWindows else Icons.Filled.Tv,  // SONY shows Tv icon
                null, tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                if (isConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("ĐANG KẾT NỐI", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    }
                }
            }
            Text("${device.brand.displayName} • ${device.ip}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
            if (device.lastConnectedMs > 0L) {
                Text(relativeTime(device.lastConnectedMs), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.35f))
            }
            if (device.ssid.isNotBlank()) {
                Text("📶 ${device.ssid}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.3f))
            }
        }
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                .clickable { onForget() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Delete, "Forget", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// relativeTime helper
// ─────────────────────────────────────────────────────────────────────────────

fun relativeTime(ms: Long): String {
    val diff  = System.currentTimeMillis() - ms
    val mins  = diff / 60_000
    val hours = diff / 3_600_000
    val days  = diff / 86_400_000
    return when {
        mins  < 1  -> "Vừa xong"
        mins  < 60 -> "$mins phút trước"
        hours < 24 -> "$hours giờ trước"
        days  < 7  -> "$days ngày trước"
        else       -> "${days / 7} tuần trước"
    }
}

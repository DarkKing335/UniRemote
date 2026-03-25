package com.example.uniremote.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.data.TvDevice

/**
 * A premium horizontal bar showing active TV devices.
 * Allows quick switching between devices and toggling Broadcast (Multi-Control) mode.
 */
@Composable
fun MultitaskingBar(
    activeDevices: List<TvDevice>,
    selectedDevice: TvDevice?,
    isBroadcast: Boolean,
    onDeviceClick: (TvDevice) -> Unit,
    onBroadcastToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1A1A1A).copy(alpha = 0.85f), // Glassmorphism base
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Broadcast Mode Toggle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBroadcast) {
                            Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7)))
                        } else {
                            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.1f)))
                        }
                    )
                    .clickable { onBroadcastToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBroadcast) Icons.Default.Radio else Icons.Default.Cast,
                    contentDescription = "Multi-Control",
                    tint = if (isBroadcast) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight(0.6f)
                    .width(1.dp),
                color = Color.White.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Active Devices List
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(activeDevices) { device ->
                    DevicePill(
                        device = device,
                        isSelected = device.id == selectedDevice?.id,
                        isBroadcast = isBroadcast,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicePill(
    device: TvDevice,
    isSelected: Boolean,
    isBroadcast: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isBroadcast -> Color(0xFF6366F1).copy(alpha = 0.2f)
            isSelected -> Color.White.copy(alpha = 0.15f)
            else -> Color.Transparent
        },
        label = "bg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isBroadcast -> Color(0xFF6366F1).copy(alpha = 0.5f)
            isSelected -> Color.White.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.05f)
        },
        label = "border"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = device.name,
                color = if (isSelected || isBroadcast) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                fontWeight = if (isSelected || isBroadcast) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            if (isSelected && !isBroadcast) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981)) // Online green dot
                )
            }
        }
    }
}

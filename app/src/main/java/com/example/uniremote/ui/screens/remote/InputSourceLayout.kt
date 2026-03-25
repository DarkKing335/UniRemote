package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.viewmodel.RemoteViewModel

private data class InputSource(
    val name: String,
    val icon: ImageVector,
    val key: TvKey
)

private val inputSources = listOf(
    InputSource("TV",         Icons.Filled.Tv,       TvKey.SOURCE),
    InputSource("HDMI 1",     Icons.Filled.Cable,    TvKey.HDMI_1),
    InputSource("HDMI 2",     Icons.Filled.Cable,    TvKey.HDMI_2),
    InputSource("HDMI 3/ARC", Icons.Filled.Cable,    TvKey.HDMI_3),
    InputSource("HDMI 4",     Icons.Filled.Cable,    TvKey.HDMI_4),
    InputSource("USB",        Icons.Filled.Usb,      TvKey.SOURCE),
    InputSource("Video",      Icons.Filled.Videocam, TvKey.AV),
    InputSource("PC",         Icons.Filled.Computer, TvKey.HDMI_1),
)

@Composable
fun InputSourceLayout(vm: RemoteViewModel? = null) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(inputSources) { source ->
            InputSourceRow(source, onTap = { vm?.sendKey(source.key) })
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 1.dp)
        }
    }
}

@Composable
private fun InputSourceRow(source: InputSource, onTap: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .remotePressable(shape = RoundedCornerShape(6.dp), raisedElevation = 6.dp, pressedElevation = 1.dp, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 90.dp, height = 72.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFDDDDDD)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = source.icon,
                contentDescription = null,
                tint = Color(0xFF333333),
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.width(20.dp))

        Text(
            text = source.name,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.3.sp
        )
    }
}

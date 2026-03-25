package com.example.uniremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.example.uniremote.ui.theme.AccentGray

@Composable
fun PremiumDPad(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .shadow(24.dp, CircleShape, spotColor = Color.Black)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2E2E33), Color(0xFF141416))
                )
            )
            .border(1.dp, Color(0xFF3F3F46), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Soft inner glow
        Box(modifier = Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color(0x1AFFFFFF), Color.Transparent))))

        // Directional arrows
        Box(modifier = Modifier.align(Alignment.TopCenter).clip(CircleShape).clickable {}.padding(top = 16.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = AccentGray, modifier = Modifier.size(52.dp))
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).clip(CircleShape).clickable {}.padding(bottom = 16.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = AccentGray, modifier = Modifier.size(52.dp))
        }
        Box(modifier = Modifier.align(Alignment.CenterStart).clip(CircleShape).clickable {}.padding(start = 16.dp)) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = AccentGray, modifier = Modifier.size(52.dp))
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).clickable {}.padding(end = 16.dp)) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = AccentGray, modifier = Modifier.size(52.dp))
        }

        // Inner SELECT button
        Box(
            modifier = Modifier
                .fillMaxSize(0.42f)
                .shadow(16.dp, CircleShape, spotColor = Color.Black)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1F1F22), Color(0xFF070708)),
                        radius = 200f
                    )
                )
                .border(2.dp, Color(0xFF27272A), CircleShape)
                .clickable { },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize(0.2f)) {
                val path = Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width / 2, size.height)
                    lineTo(0f, size.height / 2)
                    close()
                }
                drawPath(path, Brush.linearGradient(listOf(Color.White, AccentGray)))
            }
        }
    }
}

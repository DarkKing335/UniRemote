package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.ui.theme.DeepBtnBg
import com.example.uniremote.ui.theme.GlassBtnBg
import com.example.uniremote.ui.theme.GlassBtnBorder
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun MouseCursorLayout(vm: RemoteViewModel? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Browser Nav Row: ↺  ←  →  │  ACTION MENU ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DeepBtnBg)
                .border(1.dp, GlassBtnBorder, RoundedCornerShape(6.dp))
        ) {
            // Refresh
            NavIconBtn(icon = Icons.Filled.Refresh,                      modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { vm?.sendKey(TvKey.OK) })
            NavIconBtn(icon = Icons.AutoMirrored.Filled.ArrowBack,        modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { vm?.sendKey(TvKey.BACK) })
            NavIconBtn(icon = Icons.AutoMirrored.Filled.ArrowForward,     modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { vm?.sendKey(TvKey.OK) })

            // Vertical divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(GlassBtnBorder))

            // ACTION MENU (text, wider)
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .remotePressable(shape = RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ACTION\nMENU",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp
                )
            }
        }

        // ── Cursor / Screen Area + Scroll Bar ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Large black drag area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(onDrag = { _, dragAmount ->
                            vm?.moveMouse(dragAmount.x, dragAmount.y)
                        })
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { vm?.tapMouse() })
                    }
            )

            // Scroll bar – thin column with ∧ / ∨
            Column(
                modifier = Modifier
                    .width(38.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(GlassBtnBg)
                    .border(1.dp, GlassBtnBorder, RoundedCornerShape(4.dp)),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Scroll Up
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .remotePressable(shape = RoundedCornerShape(4.dp), raisedElevation = 6.dp, pressedElevation = 1.dp,
                            onClick = { vm?.sendKey(TvKey.VOL_UP) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Scroll Up",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Track indicator
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                )

                // Scroll Down
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .remotePressable(shape = RoundedCornerShape(4.dp), raisedElevation = 6.dp, pressedElevation = 1.dp,
                            onClick = { vm?.sendKey(TvKey.VOL_DOWN) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Scroll Down",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavIconBtn(icon: ImageVector, modifier: Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier = modifier
            .remotePressable(shape = RoundedCornerShape(4.dp), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.80f),
            modifier = Modifier.size(24.dp)
        )
    }
}

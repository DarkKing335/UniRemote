package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.ui.theme.CyanText
import com.example.uniremote.ui.theme.PowerGlow
import com.example.uniremote.viewmodel.RemoteViewModel
import kotlinx.coroutines.delay
import kotlin.math.max

private data class SwipeTrailPoint(
    val position: Offset,
    val createdAtMs: Long
)

private const val TRAIL_MAX_POINTS = 20
private const val TRAIL_LIFETIME_MS = 420L

@Composable
fun DPadTouchpadLayout(vm: RemoteViewModel) {
    val haptic = LocalHapticFeedback.current
    val swipeTrail = remember { mutableStateListOf<SwipeTrailPoint>() }
    val trailColors = remember {
        listOf(
            Color(0xFFFF5A5F),
            Color(0xFFFFB347),
            Color(0xFFFFF176),
            Color(0xFF66BB6A),
            Color(0xFF4FC3F7),
            Color(0xFFAB47BC)
        )
    }

    fun recordTrailPoint(position: Offset) {
        val now = System.currentTimeMillis()
        swipeTrail.add(SwipeTrailPoint(position = position, createdAtMs = now))
        val overflow = swipeTrail.size - TRAIL_MAX_POINTS
        if (overflow > 0) repeat(overflow) { swipeTrail.removeAt(0) }
    }

    LaunchedEffect(swipeTrail.size) {
        if (swipeTrail.isEmpty()) return@LaunchedEffect
        while (swipeTrail.isNotEmpty()) {
            val now = System.currentTimeMillis()
            swipeTrail.removeAll { now - it.createdAtMs > TRAIL_LIFETIME_MS }
            delay(16L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ACTION MENU + down-chevron
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ACTION MENU",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Power button (green glow)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PowerGlow.copy(alpha = 0.15f))
                    .border(1.dp, PowerGlow.copy(alpha = 0.35f), CircleShape)
                    .remotePressable(shape = CircleShape, raisedElevation = 8.dp, pressedElevation = 1.dp,
                        onClick = { vm.power() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = "Power",
                    tint = PowerGlow,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Touch/Swipe Area ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            recordTrailPoint(change.position)
                            vm.moveMouse(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        recordTrailPoint(it)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.tapMouse()
                    })
                }
        ) {
            // Dashed crosshair lines
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                val lineColor = Color.White.copy(alpha = 0.18f)
                val stroke = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect)

                // Vertical dashed line
                drawLine(
                    color = lineColor,
                    start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                    strokeWidth = stroke.width,
                    pathEffect = stroke.pathEffect
                )

                // Horizontal dashed line
                drawLine(
                    color = lineColor,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                    strokeWidth = stroke.width,
                    pathEffect = stroke.pathEffect
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val now = System.currentTimeMillis()
                swipeTrail.forEachIndexed { index, point ->
                    val age = (now - point.createdAtMs).coerceAtLeast(0L)
                    val life = 1f - (age.toFloat() / TRAIL_LIFETIME_MS.toFloat())
                    if (life > 0f) {
                        val color = trailColors[index % trailColors.size]
                        val radius = max(20f, 52f * life)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color.copy(alpha = 0.34f * life),
                                    Color.Transparent
                                ),
                                center = point.position,
                                radius = radius
                            ),
                            radius = radius,
                            center = point.position
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Footer ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FooterButton(label = "BACK", color = CyanText, onClick = { vm.sendKey(TvKey.BACK) })
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.sendKey(TvKey.MENU) 
                    }
                )
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "DISCOVER",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
            FooterButton(label = "HOME", color = CyanText, onClick = { vm.sendKey(TvKey.HOME) })
        }
    }
}

@Composable
private fun FooterButton(label: String, color: Color, onClick: () -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
    )
}

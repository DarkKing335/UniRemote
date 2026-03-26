package com.example.uniremote.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.theme.CyanText

@Composable
fun PremiumDPad(
    modifier: Modifier = Modifier,
    onUp:    () -> Unit = {},
    onDown:  () -> Unit = {},
    onLeft:  () -> Unit = {},
    onRight: () -> Unit = {},
    onOk:    () -> Unit = {}
) {
    // Premium dark glass scheme
    val dpadLight = Color(0xFF2C313C)
    val dpadDark  = Color(0xFF1E222A)
    val gapRim    = Color(0xFF0A0A0C) // Deep dark background separating buttons

    val buttonSize = 92.dp
    val gap = 6.dp
    val iconInset = 8.dp

    // Curved edges
    val outerCorner = 92.dp
    val innerCorner = 10.dp

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Rotated grid for the diagonal X look
        Box(
            modifier = Modifier.rotate(-45f),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DirectionKey(
                        shape = RoundedCornerShape(topStart = outerCorner, topEnd = innerCorner, bottomStart = innerCorner, bottomEnd = innerCorner),
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft, iconRotation = 45f,
                        iconOffsetX = -iconInset, iconOffsetY = -iconInset, size = buttonSize,
                        lightColor = dpadLight, darkColor = dpadDark, onClick = onLeft
                    )
                    DirectionKey(
                        shape = RoundedCornerShape(topStart = innerCorner, topEnd = outerCorner, bottomStart = innerCorner, bottomEnd = innerCorner),
                        icon = Icons.Rounded.KeyboardArrowUp, iconRotation = 45f,
                        iconOffsetX = iconInset, iconOffsetY = -iconInset, size = buttonSize,
                        lightColor = dpadLight, darkColor = dpadDark, onClick = onUp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DirectionKey(
                        shape = RoundedCornerShape(topStart = innerCorner, topEnd = innerCorner, bottomStart = outerCorner, bottomEnd = innerCorner),
                        icon = Icons.Rounded.KeyboardArrowDown, iconRotation = 45f,
                        iconOffsetX = -iconInset, iconOffsetY = iconInset, size = buttonSize,
                        lightColor = dpadLight, darkColor = dpadDark, onClick = onDown
                    )
                    DirectionKey(
                        shape = RoundedCornerShape(topStart = innerCorner, topEnd = innerCorner, bottomStart = innerCorner, bottomEnd = outerCorner),
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight, iconRotation = 45f,
                        iconOffsetX = iconInset, iconOffsetY = iconInset, size = buttonSize,
                        lightColor = dpadLight, darkColor = dpadDark, onClick = onRight
                    )
                }
            }
        }

        // Central OK Hub
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(gapRim), // Covers the square gaps gracefully
            contentAlignment = Alignment.Center
        ) {
            var isOkPressed by remember { mutableStateOf(false) }
            val haptic = LocalHapticFeedback.current
            val okScale by animateFloatAsState(
                targetValue = if (isOkPressed) 0.90f else 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
                label = "ok_scale"
            )

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .scale(okScale)
                    .clip(CircleShape)
                    // Vibrant Cyan Gradient matching the "REMOTE" bottom tab
                    .background(Brush.linearGradient(listOf(Color(0xFF00E5EE), Color(0xFF009CA2))))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isOkPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                tryAwaitRelease()
                                isOkPressed = false
                                onOk()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OK",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun DirectionKey(
    shape: Shape,
    icon: ImageVector,
    iconRotation: Float,
    iconOffsetX: Dp,
    iconOffsetY: Dp,
    size: Dp,
    lightColor: Color,
    darkColor: Color,
    onClick: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label = "dir_scale"
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lightColor, darkColor)))
            // Subtle frosted glass border
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        tryAwaitRelease()
                        isPressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CyanText.copy(alpha = if (isPressed) 1f else 0.7f),
            modifier = Modifier
                .offset(x = iconOffsetX, y = iconOffsetY)
                .rotate(iconRotation)
                .size(34.dp)
        )
    }
}
package com.example.uniremote.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.remotePressable(
    shape: Shape,
    onClick: () -> Unit = {},
    lift: Dp = 1.2.dp,
    pressDepth: Dp = 2.2.dp,
    raisedElevation: Dp = 12.dp,
    pressedElevation: Dp = 4.dp,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val yOffset by animateDpAsState(
        targetValue = if (isPressed) pressDepth else -lift,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "remote_press_offset"
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) pressedElevation else raisedElevation,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 720f),
        label = "remote_press_elevation"
    )

    return this
        .offset(y = yOffset)
        .shadow(
            elevation = if (isPressed) 1.dp else 4.dp,
            shape = shape,
            ambientColor = Color.White.copy(alpha = 0.05f),
            spotColor = Color.White.copy(alpha = 0.06f)
        )
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.48f),
            spotColor = Color.Black.copy(alpha = 0.66f)
        )
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

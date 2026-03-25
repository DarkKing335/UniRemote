package com.example.uniremote.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft physical press modifier applied to all remote buttons:
 *   • Scale  1.00 → 0.945  (spring bounce)
 *   • Y-shift downward by [pressDepth] dp  (layout-based, no shadow clipping)
 *   • Drop-shadow 10 → 2 dp (button appears to touch the surface)
 *   • Haptic on every tap
 *
 * Pass in a shared [interactionSource] when the caller also needs to read
 * the pressed state (e.g., PremiumBtn's color overlay).
 */
@Composable
fun Modifier.remotePressable(
    shape:             Shape,
    onClick:           () -> Unit = {},
    lift:              Dp         = 1.5.dp,
    pressDepth:        Dp         = 2.5.dp,
    raisedElevation:   Dp         = 10.dp,
    pressedElevation:  Dp         = 2.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticType:        HapticFeedbackType = HapticFeedbackType.TextHandleMove,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic    = LocalHapticFeedback.current

    val pressSpring = spring<Float>(dampingRatio = 0.65f, stiffness = 600f)
    val dpSpring    = spring<Dp>(dampingRatio = 0.65f, stiffness = 600f)

    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.945f else 1.00f,
        animationSpec = pressSpring,
        label         = "btn_scale"
    )
    val yOffset by animateDpAsState(
        targetValue   = if (isPressed) pressDepth else -lift,
        animationSpec = dpSpring,
        label         = "btn_y"
    )
    val elevation by animateDpAsState(
        targetValue   = if (isPressed) pressedElevation else raisedElevation,
        animationSpec = dpSpring,
        label         = "btn_elev"
    )

    return this
        .scale(scale)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, yOffset.roundToPx())
            }
        }
        // Soft top-edge glow at rest (disappears on press)
        .shadow(
            elevation    = if (isPressed) 0.dp else 2.dp,
            shape        = shape,
            ambientColor = Color.White.copy(alpha = 0.08f),
            spotColor    = Color.White.copy(alpha = 0.10f)
        )
        // Main bottom shadow (shrinks on press → "contact" illusion)
        .shadow(
            elevation    = elevation,
            shape        = shape,
            ambientColor = Color.Black.copy(alpha = 0.55f),
            spotColor    = Color.Black.copy(alpha = 0.70f)
        )
        .clickable(
            interactionSource = interactionSource,
            indication        = null,
            onClick           = {
                haptic.performHapticFeedback(hapticType)
                onClick()
            }
        )
}

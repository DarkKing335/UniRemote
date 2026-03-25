package com.example.uniremote.ui.components.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

@Composable
fun FadingStrip(
    alpha: Float,
    placeholderHeight: Dp,
    content: @Composable () -> Unit
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        label = "stripCrossfadeAlpha"
    )

    if (animatedAlpha > 0.01f) {
        Box(modifier = Modifier.graphicsLayer { this.alpha = animatedAlpha }) {
            content()
        }
    } else {
        Spacer(modifier = Modifier.height(placeholderHeight))
    }
}

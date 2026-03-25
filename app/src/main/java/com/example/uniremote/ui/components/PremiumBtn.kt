package com.example.uniremote.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.theme.DeepBtnBg
import com.example.uniremote.ui.theme.GlassBtnBorder

@Composable
fun PremiumBtn(
    modifier:    Modifier    = Modifier,
    text:        String?     = null,
    icon:        ImageVector?= null,
    bg:          Color       = DeepBtnBg,
    tint:        Color       = Color.White,
    textColor:   Color       = Color(0xFFE2E8F0),
    borderColor: Color       = GlassBtnBorder,
    glow:        Color       = Color.Transparent,
    shape:       Shape       = RoundedCornerShape(10.dp),
    fontSize:    TextUnit    = 11.sp,
    onClick:     () -> Unit  = {}
) {
    // Shared source — drives both remotePressable animation AND colour overlay
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Subtle white brightening on press (0 → 10% opacity)
    val pressAlpha by animateFloatAsState(
        targetValue   = if (isPressed) 0.10f else 0.00f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label         = "press_bright"
    )

    Box(
        modifier = modifier
            .shadow(7.dp, shape = shape, spotColor = glow, ambientColor = glow)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        bg.copy(alpha = 0.94f),
                        bg.copy(alpha = 1.00f),
                        Color.Black.copy(alpha = 0.28f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .border(1.dp, borderColor.copy(alpha = 0.75f), shape)
            // Passes shared interactionSource — remotePressable handles haptic + animations
            .remotePressable(
                shape             = shape,
                onClick           = onClick,
                interactionSource = interactionSource
            ),
        contentAlignment = Alignment.Center
    ) {
        // Glass sheen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.06f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.20f)
                        )
                    )
                )
        )

        // Press colour overlay — brightens button face on touch
        if (pressAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = pressAlpha))
            )
        }

        if (text != null) {
            Text(
                text          = text,
                color         = textColor,
                fontSize      = fontSize,
                fontWeight    = FontWeight.SemiBold,
                textAlign     = TextAlign.Center,
                lineHeight    = if (fontSize.value > 16f) fontSize else 14.sp,
                letterSpacing = 0.5.sp
            )
        }
        if (icon != null) {
            Icon(
                imageVector     = icon,
                contentDescription = null,
                tint            = tint,
                modifier        = Modifier.size(24.dp)
            )
        }
    }
}

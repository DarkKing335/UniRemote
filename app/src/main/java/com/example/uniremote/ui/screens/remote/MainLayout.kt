package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.PowerSettingsNew
import com.example.uniremote.ui.components.icons.MicIcon
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.uniremote.ui.components.PremiumBtn
import com.example.uniremote.ui.components.PremiumDPad
import com.example.uniremote.ui.components.remote.FadingStrip
import com.example.uniremote.ui.components.remote.SlidingActionButtonStrip
import com.example.uniremote.ui.theme.*

@Composable
fun MainLayout(pageAlpha: Float = 1f) {
    val actionStripState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Row 1 – Quick action circles: Input · Mic · Power
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PremiumBtn(modifier = Modifier.weight(1f).aspectRatio(1f), icon = Icons.AutoMirrored.Filled.Input, shape = CircleShape)
            PremiumBtn(modifier = Modifier.weight(1f).aspectRatio(1f), icon = MicIcon, bg = GlassBtnBg, shape = CircleShape)
            PremiumBtn(
                modifier = Modifier.weight(1f).aspectRatio(1f),
                icon = Icons.Filled.PowerSettingsNew,
                bg = PowerGlowDim,
                tint = PowerGlow,
                borderColor = PowerGlow.copy(alpha = 0.3f),
                glow = PowerGlow.copy(alpha = 0.2f),
                shape = CircleShape
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Row 2 – D-Pad
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(8f)
                .padding(vertical = 4.dp)
        ) {
            PremiumBtn(modifier = Modifier.align(Alignment.TopStart).fillMaxHeight(0.14f).fillMaxWidth(0.26f), text = "DISCOVER")
            PremiumBtn(modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(0.14f).fillMaxWidth(0.20f), text = "TV")
            PremiumBtn(modifier = Modifier.align(Alignment.BottomStart).fillMaxHeight(0.15f).fillMaxWidth(0.25f), text = "BACK", textColor = CyanText)
            PremiumBtn(modifier = Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.15f).fillMaxWidth(0.25f), text = "HOME", textColor = CyanText)
            PremiumDPad(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(0.75f)
                    .aspectRatio(1f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Row 3 – Vol/Prog labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(66.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(width = 24.dp, height = 18.dp)) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, color = Color.White.copy(alpha = 0.60f), style = Stroke(width = 1.8.dp.toPx()))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "PROG", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 4 – Mute | VOL −+ | PROG −+
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            PremiumBtn(modifier = Modifier.width(54.dp).fillMaxHeight(), icon = Icons.Filled.VolumeOff, bg = GlassBtnBg, shape = RoundedCornerShape(8.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PremiumBtn(modifier = Modifier.weight(1f).fillMaxHeight(), text = "−", bg = GlassBtnBg, fontSize = 22.sp, shape = RoundedCornerShape(8.dp))
                PremiumBtn(modifier = Modifier.weight(1f).fillMaxHeight(), text = "+", bg = GlassBtnBg, fontSize = 22.sp, shape = RoundedCornerShape(8.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PremiumBtn(modifier = Modifier.weight(1f).fillMaxHeight(), text = "−", bg = GlassBtnBg, fontSize = 22.sp, shape = RoundedCornerShape(8.dp))
                PremiumBtn(modifier = Modifier.weight(1f).fillMaxHeight(), text = "+", bg = GlassBtnBg, fontSize = 22.sp, shape = RoundedCornerShape(8.dp))
            }
        }

        Spacer(modifier = Modifier.weight(0.8f))

        FadingStrip(alpha = pageAlpha, placeholderHeight = 56.dp) {
            SlidingActionButtonStrip(listState = actionStripState)
        }
    }
}

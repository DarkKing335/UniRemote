package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.components.remotePressable
import androidx.compose.ui.graphics.Color
import com.example.uniremote.ui.components.remote.FadingStrip
import com.example.uniremote.ui.components.remote.SlidingActionButtonStrip
import com.example.uniremote.ui.components.remote.SlidingColorButtonStrip
import com.example.uniremote.ui.theme.DeepBtnBg
import com.example.uniremote.ui.theme.GlassBtnBorder

private data class NumpadEntry(
    val text: String? = null,
    val icon: ImageVector? = null
)

@Composable
private fun SegmentedNumpadRow(entries: List<NumpadEntry>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DeepBtnBg)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        entries.forEachIndexed { index, entry ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .remotePressable(shape = RectangleShape, raisedElevation = 5.dp, pressedElevation = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                if (entry.text != null) {
                    Text(text = entry.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                if (entry.icon != null) {
                    Icon(imageVector = entry.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            if (index < entries.lastIndex) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(GlassBtnBorder.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
fun NumpadLayout(pageAlpha: Float = 1f) {
    val actionStripState = rememberLazyListState()
    val colorStripState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FadingStrip(alpha = pageAlpha, placeholderHeight = 56.dp) {
            SlidingActionButtonStrip(listState = actionStripState)
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedNumpadRow(listOf(NumpadEntry("1"), NumpadEntry("2"), NumpadEntry("3")))
                SegmentedNumpadRow(listOf(NumpadEntry("4"), NumpadEntry("5"), NumpadEntry("6")))
                SegmentedNumpadRow(listOf(NumpadEntry("7"), NumpadEntry("8"), NumpadEntry("9")))
                SegmentedNumpadRow(listOf(NumpadEntry(icon = Icons.Filled.Contrast), NumpadEntry("0"), NumpadEntry(icon = Icons.Filled.Menu)))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        FadingStrip(alpha = pageAlpha, placeholderHeight = 48.dp) {
            SlidingColorButtonStrip(listState = colorStripState)
        }
    }
}

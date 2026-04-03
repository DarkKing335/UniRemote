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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.remote.FadingStrip
import com.example.uniremote.ui.components.remote.SlidingActionButtonStrip
import com.example.uniremote.ui.components.remote.SlidingColorButtonStrip
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.ui.theme.DeepBtnBg
import com.example.uniremote.ui.theme.GlassBtnBorder
import com.example.uniremote.viewmodel.RemoteViewModel

private data class NumpadEntry(
    val text: String? = null,
    val icon: ImageVector? = null,
    val key: TvKey? = null
)

@Composable
private fun SegmentedNumpadRow(entries: List<NumpadEntry>, vm: RemoteViewModel) {
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
                    .remotePressable(shape = RectangleShape, raisedElevation = 5.dp, pressedElevation = 1.dp,
                        onClick = { entry.key?.let { vm.sendKey(it) } }),
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
fun NumpadLayout(pageAlpha: Float = 1f, vm: RemoteViewModel) {
    val actionStripState = rememberLazyListState()
    val colorStripState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FadingStrip(alpha = pageAlpha, placeholderHeight = 56.dp) {
            SlidingActionButtonStrip(listState = actionStripState, vm = vm)
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedNumpadRow(listOf(
                    NumpadEntry("1", key = TvKey.NUM_1),
                    NumpadEntry("2", key = TvKey.NUM_2),
                    NumpadEntry("3", key = TvKey.NUM_3)
                ), vm)
                SegmentedNumpadRow(listOf(
                    NumpadEntry("4", key = TvKey.NUM_4),
                    NumpadEntry("5", key = TvKey.NUM_5),
                    NumpadEntry("6", key = TvKey.NUM_6)
                ), vm)
                SegmentedNumpadRow(listOf(
                    NumpadEntry("7", key = TvKey.NUM_7),
                    NumpadEntry("8", key = TvKey.NUM_8),
                    NumpadEntry("9", key = TvKey.NUM_9)
                ), vm)
                SegmentedNumpadRow(listOf(
                    NumpadEntry(icon = Icons.Filled.Contrast, key = TvKey.ASPECT_RATIO),
                    NumpadEntry("0", key = TvKey.NUM_0),
                    NumpadEntry(icon = Icons.Filled.Menu, key = TvKey.MENU)
                ), vm)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        FadingStrip(alpha = pageAlpha, placeholderHeight = 48.dp) {
            SlidingColorButtonStrip(listState = colorStripState)
        }
    }
}

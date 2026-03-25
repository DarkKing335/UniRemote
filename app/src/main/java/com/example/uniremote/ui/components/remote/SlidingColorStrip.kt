package com.example.uniremote.ui.components.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.uniremote.ui.components.PremiumBtn

private data class ColorStripItem(val tint: Color)

@Composable
fun SlidingColorButtonStrip(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val stripShape = RoundedCornerShape(8.dp)
    val stripGap = 12.dp
    val colors = listOf(
        ColorStripItem(Color.Red),
        ColorStripItem(Color.Green),
        ColorStripItem(Color.Yellow),
        ColorStripItem(Color.Blue),
        ColorStripItem(Color.Cyan),
        ColorStripItem(Color.Magenta)
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val itemWidth = (this.maxWidth - (stripGap * 3)) / 4

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(stripGap)
        ) {
            items(colors) { item ->
                PremiumBtn(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(48.dp),
                    icon = Icons.Filled.HorizontalRule,
                    tint = item.tint,
                    shape = stripShape
                )
            }
        }
    }
}

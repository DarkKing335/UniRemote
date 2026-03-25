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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.components.PremiumBtn

private data class ActionStripItem(
    val text: String? = null,
    val icon: ImageVector? = null,
    val fontSize: TextUnit = 12.sp
)

@Composable
fun SlidingActionButtonStrip(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val stripShape = RoundedCornerShape(8.dp)
    val stripGap = 12.dp
    val actions = listOf(
        ActionStripItem(text = "GUIDE", fontSize = 11.sp),
        ActionStripItem(text = "ACTION\nMENU", fontSize = 10.sp),
        ActionStripItem(text = "DIGITAL/\nANALOG", fontSize = 10.sp),
        ActionStripItem(text = "EXIT", fontSize = 12.sp),
        ActionStripItem(icon = Icons.Filled.Sync),
        ActionStripItem(text = "INPUT", fontSize = 11.sp),
        ActionStripItem(text = "HOME", fontSize = 11.sp),
        ActionStripItem(text = "BACK", fontSize = 11.sp)
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val itemWidth = (this.maxWidth - (stripGap * 2)) / 3

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(stripGap)
        ) {
            items(actions) { item ->
                PremiumBtn(
                    modifier = Modifier.width(itemWidth).height(56.dp),
                    text = item.text,
                    icon = item.icon,
                    fontSize = item.fontSize,
                    shape = stripShape
                )
            }
        }
    }
}

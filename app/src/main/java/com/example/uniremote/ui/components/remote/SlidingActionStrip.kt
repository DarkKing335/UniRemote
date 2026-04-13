package com.example.uniremote.ui.components.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.PremiumBtn
import com.example.uniremote.viewmodel.RemoteViewModel

private data class ActionStripItem(
    val text:     String?      = null,
    val icon:     ImageVector? = null,
    val fontSize: TextUnit     = 12.sp,
    val key:      TvKey?       = null
)

@Composable
fun SlidingActionButtonStrip(
    listState: LazyListState,
    vm:        RemoteViewModel,
    modifier:  Modifier = Modifier
) {
    val stripShape = RoundedCornerShape(8.dp)
    val stripGap   = 12.dp
    val actions = listOf(
        ActionStripItem(text = "GUIDE",             fontSize = 11.sp, key = TvKey.GUIDE),
        ActionStripItem(text = "ACTION\nMENU",      fontSize = 10.sp, key = TvKey.MENU),
        ActionStripItem(text = "DIGITAL/\nANALOG",  fontSize = 10.sp, key = TvKey.CH_UP),
        ActionStripItem(text = "EXIT",               fontSize = 12.sp, key = TvKey.EXIT),
        ActionStripItem(icon = Icons.Filled.Sync,                     key = TvKey.SOURCE),
        ActionStripItem(text = "INPUT",              fontSize = 11.sp, key = TvKey.SOURCE),
        ActionStripItem(text = "HOME",               fontSize = 11.sp, key = TvKey.HOME),
        ActionStripItem(text = "BACK",               fontSize = 11.sp, key = TvKey.BACK)
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val itemWidth = (this.maxWidth - (stripGap * 2)) / 3
        val sweepShift = (listState.firstVisibleItemIndex * 100 + listState.firstVisibleItemScrollOffset).toFloat()
        val overlayShape = RoundedCornerShape(10.dp)
        val strokeWidth = 2.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(overlayShape)
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(stripGap)
            ) {
                items(actions) { item ->
                    PremiumBtn(
                        modifier = Modifier.width(itemWidth).height(56.dp),
                        text     = item.text,
                        icon     = item.icon,
                        fontSize = item.fontSize,
                        shape    = stripShape,
                        onClick  = {
                            when (item.key) {
                                TvKey.GUIDE -> vm.triggerGuideAction()
                                TvKey.MENU -> vm.triggerActionMenu()
                                TvKey.CH_UP -> vm.triggerDigitalAnalogAction()
                                TvKey.SOURCE -> vm.triggerSourceAction()
                                else -> item.key?.let { vm.sendKey(it) }
                            }
                        }
                    )
                }
            }

            Canvas(modifier = Modifier.matchParentSize()) {
                val width = size.width
                val beamStart = (sweepShift % (width + 220f)) - 220f
                val beamEnd = beamStart + 220f

                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x66FF595E),
                            Color(0x66FFCA3A),
                            Color(0x668AC926),
                            Color(0x66198BE2),
                            Color.Transparent
                        ),
                        start = Offset(beamStart, 0f),
                        end = Offset(beamEnd, size.height)
                    ),
                    blendMode = BlendMode.Screen
                )

                // Subtle neon rail so the strip reads as active during swipe.
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x66FF595E),
                            Color(0x66FFCA3A),
                            Color(0x668AC926),
                            Color(0x66198BE2)
                        ),
                        startX = 0f,
                        endX = width
                    ),
                    start = Offset(0f, size.height - strokeWidth.toPx()),
                    end = Offset(width, size.height - strokeWidth.toPx()),
                    strokeWidth = strokeWidth.toPx(),
                    cap = Stroke.DefaultCap
                )
            }
        }
    }
}

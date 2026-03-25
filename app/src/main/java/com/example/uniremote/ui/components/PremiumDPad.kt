package com.example.uniremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PremiumDPad(modifier: Modifier = Modifier) {
    val orangeLight = Color(0xFFFF6A2A)
    val orangeDark = Color(0xFFD94A18)
    val rimColor = Color(0xFF141414) // Màu của rãnh chữ X và nền viền giữa

    val buttonSize = 92.dp
    val gap = 6.dp
    val iconInset = 8.dp

    // Giữ vòng ngoài tròn, chỉ bo nhẹ các góc giao bị nhọn.
    val outerCorner = 92.dp
    val innerCorner = 10.dp

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Xoay khối 4 nút 45 độ để tạo rãnh chéo
        Box(
            modifier = Modifier.rotate(-45f),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Góc top-left sau khi xoay sẽ nằm bên TRÁI
                    DirectionKey(
                        shape = RoundedCornerShape(
                            topStart = outerCorner, topEnd = innerCorner,
                            bottomStart = innerCorner, bottomEnd = innerCorner
                        ),
                        icon = Icons.Rounded.KeyboardArrowLeft,
                        iconRotation = 45f,
                        iconOffsetX = -iconInset, iconOffsetY = -iconInset,
                        size = buttonSize,
                        lightColor = orangeLight, darkColor = orangeDark
                    )
                    // Góc top-right sau khi xoay sẽ nằm bên TRÊN
                    DirectionKey(
                        shape = RoundedCornerShape(
                            topStart = innerCorner, topEnd = outerCorner,
                            bottomStart = innerCorner, bottomEnd = innerCorner
                        ),
                        icon = Icons.Rounded.KeyboardArrowUp,
                        iconRotation = 45f,
                        iconOffsetX = iconInset, iconOffsetY = -iconInset,
                        size = buttonSize,
                        lightColor = orangeLight, darkColor = orangeDark
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Góc bottom-left sau khi xoay sẽ nằm bên DƯỚI
                    DirectionKey(
                        shape = RoundedCornerShape(
                            topStart = innerCorner, topEnd = innerCorner,
                            bottomStart = outerCorner, bottomEnd = innerCorner
                        ),
                        icon = Icons.Rounded.KeyboardArrowDown,
                        iconRotation = 45f,
                        iconOffsetX = -iconInset, iconOffsetY = iconInset,
                        size = buttonSize,
                        lightColor = orangeLight, darkColor = orangeDark
                    )
                    // Góc bottom-right sau khi xoay sẽ nằm bên PHẢI
                    DirectionKey(
                        shape = RoundedCornerShape(
                            topStart = innerCorner, topEnd = innerCorner,
                            bottomStart = innerCorner, bottomEnd = outerCorner
                        ),
                        icon = Icons.Rounded.KeyboardArrowRight,
                        iconRotation = 45f,
                        iconOffsetX = iconInset, iconOffsetY = iconInset,
                        size = buttonSize,
                        lightColor = orangeLight, darkColor = orangeDark
                    )
                }
            }
        }

        // Cụm Hub Nút OK ở giữa che lấp hoàn toàn phần góc vuông
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(rimColor),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .shadow(8.dp, CircleShape, spotColor = Color.Black)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFF7A45), orangeDark)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    // Nhớ thay bằng modifier .remotePressable của bạn nhé
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OK",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun DirectionKey(
    shape: Shape,
    icon: ImageVector,
    iconRotation: Float,
    iconOffsetX: Dp,
    iconOffsetY: Dp,
    size: Dp,
    lightColor: Color,
    darkColor: Color
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(6.dp, shape, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(lightColor, darkColor)
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape)
            // Nhớ thay bằng modifier .remotePressable của bạn ở đây
            .clickable { },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .offset(x = iconOffsetX, y = iconOffsetY)
                .rotate(iconRotation)
                .size(32.dp)
        )
    }
}
package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tv
import com.example.uniremote.ui.components.icons.MicIcon
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.PremiumBtn
import com.example.uniremote.ui.components.PremiumDPad
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.ui.components.remote.FadingStrip
import com.example.uniremote.ui.components.remote.SlidingActionButtonStrip
import com.example.uniremote.ui.theme.*
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun MainLayout(pageAlpha: Float = 1f, vm: RemoteViewModel) {
    val actionStripState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        val shellShape = RoundedCornerShape(34.dp)

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shellShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1A2029),
                            Color(0xFF11161E),
                            Color(0xFF090D12)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), shellShape)
                .border(1.dp, Color.Black.copy(alpha = 0.55f), shellShape)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.74f)
                .height(170.dp)
                .offset(y = (-20).dp)
                .clip(RoundedCornerShape(140.dp))
                .background(
                    Brush.radialGradient(
                        listOf(
                            CyanText.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.80f)
                .height(210.dp)
                .offset(y = 46.dp)
                .clip(RoundedCornerShape(180.dp))
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFFFF6A35).copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top quick actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PremiumBtn(
                    modifier = Modifier.size(60.dp),
                    icon = Icons.AutoMirrored.Filled.Input,
                    shape = CircleShape,
                    bg = DeepBtnBg,
                    onClick = { vm.sendKey(TvKey.SOURCE) }
                )
                PremiumBtn(
                    modifier = Modifier.size(88.dp),
                    icon = MicIcon,
                    shape = CircleShape,
                    bg = GlassBtnBg,
                    glow = CyanText.copy(alpha = 0.25f),
                    onClick = { vm.sendKey(TvKey.SEARCH) }
                )
                PowerCircleButton(modifier = Modifier.size(66.dp), onClick = { vm.power() })
            }

            Spacer(modifier = Modifier.height(10.dp))

            // D-pad stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                PremiumBtn(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(84.dp)
                        .height(46.dp),
                    icon = Icons.Filled.Explore,
                    shape = RoundedCornerShape(14.dp),
                    bg = Color(0xFF222B37),
                    onClick = { vm.sendKey(TvKey.MENU) }
                )
                PremiumBtn(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(84.dp)
                        .height(46.dp),
                    icon = Icons.Filled.Tv,
                    shape = RoundedCornerShape(14.dp),
                    bg = Color(0xFF222B37),
                    onClick = { vm.sendKey(TvKey.INFO) }
                )
                PremiumBtn(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .width(84.dp)
                        .height(46.dp),
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    shape = RoundedCornerShape(14.dp),
                    bg = Color(0xFF222B37),
                    tint = CyanText,
                    onClick = { vm.sendKey(TvKey.BACK) }
                )
                PremiumBtn(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .width(84.dp)
                        .height(46.dp),
                    icon = Icons.Filled.Home,
                    shape = RoundedCornerShape(14.dp),
                    bg = Color(0xFF222B37),
                    tint = CyanText,
                    onClick = { vm.sendKey(TvKey.HOME) }
                )

                PremiumDPad(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight(0.83f)
                        .aspectRatio(1f),
                    onUp    = { vm.sendKey(TvKey.UP)    },
                    onDown  = { vm.sendKey(TvKey.DOWN)  },
                    onLeft  = { vm.sendKey(TvKey.LEFT)  },
                    onRight = { vm.sendKey(TvKey.RIGHT) },
                    onOk    = { vm.sendKey(TvKey.OK)    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom control deck
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(98.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PremiumBtn(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight(),
                    icon = Icons.AutoMirrored.Filled.VolumeOff,
                    bg = Color(0xFF1E2631),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { vm.mute() }
                )

                ControlPair(
                    modifier = Modifier.weight(1f),
                    title = "VOL",
                    onMinus = { vm.volumeDown() },
                    onPlus  = { vm.volumeUp() }
                )
                ControlPair(
                    modifier = Modifier.weight(1f),
                    title = "PROG",
                    onMinus = { vm.channelDown() },
                    onPlus  = { vm.channelUp() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            FadingStrip(alpha = pageAlpha, placeholderHeight = 56.dp) {
                SlidingActionButtonStrip(listState = actionStripState, vm = vm)
            }
        }
    }
}

@Composable
private fun ControlPair(
    modifier: Modifier = Modifier,
    title: String,
    onMinus: () -> Unit = {},
    onPlus:  () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PremiumBtn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                text = "-",
                bg = Color(0xFF1E2631),
                fontSize = 22.sp,
                shape = RoundedCornerShape(12.dp),
                onClick = onMinus
            )
            PremiumBtn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                text = "+",
                bg = Color(0xFF1E2631),
                fontSize = 22.sp,
                shape = RoundedCornerShape(12.dp),
                onClick = onPlus
            )
        }
    }
}

@Composable
private fun PowerCircleButton(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier = modifier
            .shadow(16.dp, CircleShape, spotColor = Color(0xAA00FF73))
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38E67A),
                        Color(0xFF1ABA5D),
                        Color(0xFF0D7B3D)
                    ),
                    radius = 180f
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
            .padding(2.dp)
            .remotePressable(shape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.18f)
                        )
                    )
                )
        )

        Icon(
            imageVector = Icons.Filled.PowerSettingsNew,
            contentDescription = "Power",
            tint = Color(0xFFD8FFE8),
            modifier = Modifier.size(28.dp)
        )
    }
}

package com.example.uniremote.ui.screens.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.PremiumBtn
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun MediaPlaybackLayout(vm: RemoteViewModel) {
    val btnShape = RoundedCornerShape(8.dp)
    val gap = 8.dp
    val rowHeight = 70.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1 – Color buttons: Red | Green | Yellow | Blue
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PremiumBtn(modifier = Modifier.weight(1f).height(58.dp), icon = Icons.Filled.HorizontalRule, tint = Color(0xFFE53935), shape = btnShape, onClick = { vm.sendKey(TvKey.RED)    })
            PremiumBtn(modifier = Modifier.weight(1f).height(58.dp), icon = Icons.Filled.HorizontalRule, tint = Color(0xFF43A047), shape = btnShape, onClick = { vm.sendKey(TvKey.GREEN)  })
            PremiumBtn(modifier = Modifier.weight(1f).height(58.dp), icon = Icons.Filled.HorizontalRule, tint = Color(0xFFFDD835), shape = btnShape, onClick = { vm.sendKey(TvKey.YELLOW) })
            PremiumBtn(modifier = Modifier.weight(1f).height(58.dp), icon = Icons.Filled.HorizontalRule, tint = Color(0xFF1E88E5), shape = btnShape, onClick = { vm.sendKey(TvKey.BLUE)   })
        }

        // Row 2 – Transport: |◄◄  ⏸  ■  ►► |
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.SkipPrevious, shape = btnShape, onClick = { vm.sendKey(TvKey.PREV)  })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.Pause,        shape = btnShape, onClick = { vm.sendKey(TvKey.PAUSE) })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.Stop,         shape = btnShape, onClick = { vm.sendKey(TvKey.STOP)  })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.SkipNext,     shape = btnShape, onClick = { vm.sendKey(TvKey.NEXT)  })
        }

        // Row 3 – Playback speed: ◄◄  ►(play, wider)  ►►
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight),   icon = Icons.Filled.FastRewind,  shape = btnShape, onClick = { vm.sendKey(TvKey.RW)   })
            PremiumBtn(modifier = Modifier.weight(1.5f).height(rowHeight), icon = Icons.Filled.PlayArrow,   shape = btnShape, onClick = { vm.sendKey(TvKey.PLAY) })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight),   icon = Icons.Filled.FastForward, shape = btnShape, onClick = { vm.sendKey(TvKey.FF)    })
        }

        // Row 4 – Language | Subtitle | AUDIO | Sleep
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.Language,  shape = btnShape, onClick = { vm.sendKey(TvKey.SOURCE)   })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.Subtitles, shape = btnShape, onClick = { vm.sendKey(TvKey.GUIDE)    })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), text = "AUDIO", fontSize = 11.sp, shape = btnShape, onClick = { vm.sendKey(TvKey.SETTINGS) })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), icon = Icons.Filled.Bedtime,   shape = btnShape, onClick = { vm.sendKey(TvKey.SLEEP)    })
        }

        // Row 5 – SYNC MENU | FOOTBALL | HELP
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), text = "SYNC\nMENU", fontSize = 10.sp, shape = btnShape, onClick = { vm.sendKey(TvKey.MENU)  })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), text = "FOOTBALL",   fontSize = 10.sp, shape = btnShape, onClick = { vm.sendKey(TvKey.GUIDE) })
            PremiumBtn(modifier = Modifier.weight(1f).height(rowHeight), text = "HELP",       fontSize = 11.sp, shape = btnShape, onClick = { vm.sendKey(TvKey.INFO)  })
        }
    }
}

package com.example.uniremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class NavigationTab {
    REMOTE, APPS, CAST, SETTINGS
}

@Composable
fun BottomNavBar(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(
            icon = Icons.Filled.SettingsRemote,
            label = "REMOTE",
            isSelected = currentTab == NavigationTab.REMOTE,
            onClick = { onTabSelected(NavigationTab.REMOTE) }
        )
        NavItem(
            icon = Icons.Filled.Apps,
            label = "APPS",
            isSelected = currentTab == NavigationTab.APPS,
            onClick = { onTabSelected(NavigationTab.APPS) }
        )
        NavItem(
            icon = Icons.Filled.Cast,
            label = "CAST",
            isSelected = currentTab == NavigationTab.CAST,
            onClick = { onTabSelected(NavigationTab.CAST) }
        )
        NavItem(
            icon = Icons.Filled.Settings,
            label = "SETTINGS",
            isSelected = currentTab == NavigationTab.SETTINGS,
            onClick = { onTabSelected(NavigationTab.SETTINGS) }
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val modifier = if (isSelected) {
        Modifier
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            )
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .then(modifier)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

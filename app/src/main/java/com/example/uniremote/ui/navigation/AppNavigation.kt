package com.example.uniremote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.screens.AppsScreen
import com.example.uniremote.ui.screens.CastScreen
import com.example.uniremote.ui.screens.MainRemoteScreen
import com.example.uniremote.ui.screens.SettingsScreen

@Composable
fun AppNavigation() {
    var currentScreen by rememberSaveable { mutableStateOf(NavigationTab.REMOTE) }

    when (currentScreen) {
        NavigationTab.REMOTE -> MainRemoteScreen(onNavigate = { currentScreen = it })
        NavigationTab.APPS -> AppsScreen(onNavigate = { currentScreen = it })
        NavigationTab.CAST -> CastScreen(onNavigate = { currentScreen = it })
        NavigationTab.SETTINGS -> SettingsScreen(onNavigate = { currentScreen = it })
    }
}

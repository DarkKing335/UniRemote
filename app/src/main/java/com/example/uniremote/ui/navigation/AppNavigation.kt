package com.example.uniremote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.screens.AppsScreen
import com.example.uniremote.ui.screens.CastScreen
import com.example.uniremote.ui.screens.MainRemoteScreen
import com.example.uniremote.ui.screens.SettingsScreen
import com.example.uniremote.viewmodel.RemoteViewModel

@Composable
fun AppNavigation() {
    var currentScreen by rememberSaveable { mutableStateOf(NavigationTab.REMOTE) }
    // Single ViewModel shared across all screens
    val vm: RemoteViewModel = viewModel()

    when (currentScreen) {
        NavigationTab.REMOTE   -> MainRemoteScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.APPS     -> AppsScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.CAST     -> CastScreen(vm = vm, onNavigate = { currentScreen = it })
        NavigationTab.SETTINGS -> SettingsScreen(vm = vm, onNavigate = { currentScreen = it })
    }
}

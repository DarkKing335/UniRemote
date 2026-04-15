package com.example.uniremote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.navigation.AppNavigation
import com.example.uniremote.ui.theme.UniRemoteTheme
import com.example.uniremote.viewmodel.RemoteViewModel
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val remoteViewModel: RemoteViewModel by viewModels()
    private val navigationEvents = MutableSharedFlow<NavigationTab>(extraBufferCapacity = 1)

    private val rokuBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "QUERY_APPS_COMPLETED" -> {
                    if (!remoteViewModel.isLoadingApps.value) {
                        remoteViewModel.loadInstalledApps()
                    }
                }

                "ROKU_NETWORK_ERROR" -> {
                    remoteViewModel.disconnect()
                    remoteViewModel.scanDevices()
                    navigationEvents.tryEmit(NavigationTab.SETTINGS)
                }
            }
        }
    }

    // Runtime permission launcher for ACCESS_FINE_LOCATION.
    // Required on Android 8–9 (API 26–28) to read the WiFi SSID via WifiManager.
    // On Android 10+ the ConnectivityManager path is used and no runtime permission is needed
    // (ACCESS_NETWORK_STATE is a normal permission already granted at install time).
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* SSID reading proceeds; null if denied — WifiUtil handles the null gracefully */ }

    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Discovery still proceeds with best effort if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Required by the dadb ADB library to locate its RSA key-pair files.
        // Must be set before any AndroidTvController is created.
        System.setProperty("user.home", filesDir.absolutePath)

        // Request ACCESS_FINE_LOCATION on Android 8–9 for SSID detection.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        // Enable Immersive Mode (hide status and navigation bars)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        enableEdgeToEdge()

        setContent {
            UniRemoteTheme {
                AppNavigation(
                    vm = remoteViewModel,
                    externalNavigationEvents = navigationEvents
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            rokuBroadcastReceiver,
            IntentFilter("QUERY_APPS_COMPLETED")
        )
        localBroadcastManager.registerReceiver(
            rokuBroadcastReceiver,
            IntentFilter("ROKU_NETWORK_ERROR")
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rokuBroadcastReceiver)
        super.onStop()
    }
}
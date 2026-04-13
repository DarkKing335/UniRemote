package com.example.uniremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "WifiUtil"

object WifiUtil {

    /**
     * Returns the current connected WiFi SSID, or null if unavailable.
     *
     * ✅ Strips surrounding quotes Android sometimes adds (e.g. `"MyWifi"` → `MyWifi`)
     * ✅ Uses the non-deprecated WifiInfo from NetworkCapabilities on Android 12+
     *
     * Requires permissions:
     *  - ACCESS_WIFI_STATE         (always)
     *  - ACCESS_FINE_LOCATION      (Android 8.0–11 via WifiManager)
    *  - NEARBY_WIFI_DEVICES       (Android 13+)
     *  - ACCESS_NETWORK_STATE      (Android 10+ via ConnectivityManager)
     */
    fun getCurrentSsid(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): use WifiInfo from NetworkCapabilities.transportInfo.
                // This is the only non-deprecated path on API 31+.
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
                val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
                wifiInfo.ssid?.stripSsidQuotes()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10–11: ConnectivityManager for network check, WifiManager for SSID.
                // WifiManager.connectionInfo is deprecated in API 31 but still functional on API 29–30.
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.connectionInfo?.ssid?.stripSsidQuotes()
            } else {
                // Android 8–9: WifiManager directly (needs ACCESS_FINE_LOCATION runtime permission).
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.connectionInfo?.ssid?.stripSsidQuotes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SSID: ${e.message}")
            null
        }
    }

    fun observeCurrentSsid(context: Context): Flow<String?> = callbackFlow {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        trySend(getCurrentSsid(appContext))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentSsid(appContext))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(getCurrentSsid(appContext))
            }

            override fun onLost(network: Network) {
                trySend(getCurrentSsid(appContext))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /** Strip surrounding quotes Android adds to SSID strings */
    private fun String.stripSsidQuotes(): String? {
        val cleaned = this.replace("\"", "").trim()
        // Android returns "<unknown ssid>" when SSID is not available
        if (cleaned.isBlank() || cleaned == "<unknown ssid>") return null
        return cleaned
    }
}


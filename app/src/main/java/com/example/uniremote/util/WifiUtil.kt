package com.example.uniremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

private const val TAG = "WifiUtil"

object WifiUtil {

    /**
     * Returns the current connected WiFi SSID, or null if unavailable.
     *
     * ✅ Strips surrounding quotes Android sometimes adds (e.g. `"MyWifi"` → `MyWifi`)
     *
     * Requires permissions:
     *  - ACCESS_WIFI_STATE
     *  - ACCESS_FINE_LOCATION (Android 8.0–9.0)
     *  - ACCESS_NETWORK_STATE (Android 10+)
     */
    fun getCurrentSsid(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: use ConnectivityManager NetworkCapabilities
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo ?: return null
                info.ssid?.stripSsidQuotes()
            } else {
                // Android 8–9: WifiManager directly (needs location permission)
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo ?: return null
                info.ssid?.stripSsidQuotes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SSID: ${e.message}")
            null
        }
    }

    /** ✅ Strip surrounding quotes Android adds to SSID strings */
    private fun String.stripSsidQuotes(): String? {
        val cleaned = this.replace("\"", "").trim()
        // Android returns "<unknown ssid>" when SSID is not available
        if (cleaned.isBlank() || cleaned == "<unknown ssid>") return null
        return cleaned
    }
}

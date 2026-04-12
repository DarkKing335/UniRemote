package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.BuildConfig

/**
 * Central policy for insecure protocol compatibility gates.
 *
 * Release builds keep these paths disabled by default.
 */
object TransportSecurityPolicy {
    private const val TAG = "TransportSecurity"

    fun allowInsecureDeviceProtocol(protocol: String): Boolean {
        if (BuildConfig.ENABLE_INSECURE_DEVICE_PROTOCOLS) {
            Log.w(TAG, "Insecure device protocol compatibility enabled: $protocol")
            return true
        }
        Log.w(TAG, "Blocked insecure device protocol in production mode: $protocol")
        return false
    }

    fun allowInsecureDlnaCasting(): Boolean {
        if (BuildConfig.ENABLE_INSECURE_DLNA_CASTING) {
            Log.w(TAG, "Insecure DLNA casting compatibility enabled")
            return true
        }
        Log.w(TAG, "Blocked insecure DLNA casting in production mode")
        return false
    }
}

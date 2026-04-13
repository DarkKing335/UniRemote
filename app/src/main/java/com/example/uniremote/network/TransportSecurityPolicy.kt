package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.BuildConfig
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Central policy for insecure protocol compatibility gates.
 *
 * Release builds keep these paths disabled by default.
 */
object TransportSecurityPolicy {
    private const val TAG = "TransportSecurity"

    fun allowInsecureDeviceProtocol(protocol: String, host: String? = null): Boolean {
        if (BuildConfig.ENABLE_INSECURE_DEVICE_PROTOCOLS) {
            if (!host.isNullOrBlank() && !isLanHost(host)) {
                logWarn("Blocked insecure device protocol for non-LAN host: protocol=$protocol host=$host")
                return false
            }
            logWarn("Insecure device protocol compatibility enabled: $protocol")
            return true
        }
        logWarn("Blocked insecure device protocol in production mode: $protocol")
        return false
    }

    fun allowInsecureDlnaCasting(): Boolean {
        if (BuildConfig.ENABLE_INSECURE_DLNA_CASTING) {
            logWarn("Insecure DLNA casting compatibility enabled")
            return true
        }
        logWarn("Blocked insecure DLNA casting in production mode")
        return false
    }

    fun allowCleartextForUrl(url: String, flow: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "ws") {
            return true
        }

        val host = parsed.host
        if (host.isNullOrBlank()) {
            logWarn("Blocked cleartext $flow: missing URL host")
            return false
        }

        if (!isLanHost(host)) {
            logWarn("Blocked cleartext $flow to non-LAN host: $host")
            return false
        }
        return true
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun isLanHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US)
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "0.0.0.0" || normalized == "::1") {
            return true
        }
        if (normalized.endsWith(".local")) {
            return true
        }

        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }

        if (address is Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if ((first and 0xFE) == 0xFC) {
                return true
            }
        }

        return false
    }
}

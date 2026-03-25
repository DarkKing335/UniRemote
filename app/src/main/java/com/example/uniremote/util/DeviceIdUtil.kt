package com.example.uniremote.util

/**
 * Creates a stable, collision-resistant device identifier.
 *
 * Priority:
 *  1. MAC address (globally unique, survives IP changes)
 *  2. Deterministic hash of IP + device name (fallback when MAC unknown)
 *
 * ✅ Avoids using raw IP as ID — IP addresses can change on DHCP networks.
 */
object DeviceIdUtil {
    fun stableId(mac: String, ip: String, name: String): String =
        mac.sanitized() ?: "$ip-$name".hashCode().toString()

    private fun String.sanitized(): String? {
        val cleaned = this.replace(":", "").replace("-", "").trim().uppercase()
        // All-zero MAC or empty = unknown
        if (cleaned.isBlank() || cleaned == "000000000000") return null
        return cleaned
    }
}

package com.example.uniremote.util

import java.security.MessageDigest

/**
 * Creates a stable, collision-resistant device identifier.
 *
 * Priority:
 *  1. MAC address (globally unique, survives IP changes)
 *  2. SHA-256 hash of IP + device name (16 hex chars, collision-resistant fallback)
 *
 * ✅ Avoids using raw IP as ID — IP addresses can change on DHCP networks.
 * ✅ Uses SHA-256 truncated to 64 bits instead of Java's 32-bit hashCode.
 */
object DeviceIdUtil {
    fun stableId(mac: String, ip: String, name: String): String =
        mac.sanitized() ?: sha256Short("$ip-$name")

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        // Take first 8 bytes → 16 hex chars (64-bit ID, collision probability negligible)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitized(): String? {
        val cleaned = this.replace(":", "").replace("-", "").trim().uppercase()
        // All-zero MAC or empty = unknown
        if (cleaned.isBlank() || cleaned == "000000000000") return null
        return cleaned
    }
}


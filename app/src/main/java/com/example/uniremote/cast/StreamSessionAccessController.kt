package com.example.uniremote.cast

import android.util.Base64
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Transport-agnostic session access controller for streaming protocols.
 *
 * The controller validates a per-session bearer token with TTL and optional
 * client IP restrictions. It is intentionally protocol-neutral so it can be
 * reused for HTTP, RTSP, or HLS access layers.
 */
class StreamSessionAccessController(
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {

    enum class ClientRestrictionMode {
        NONE,
        SAME_SUBNET,
        FIRST_CLIENT
    }

    data class SessionCredentials(
        val token: String,
        val expiresAtMs: Long
    )

    sealed class ValidationResult {
        object Allowed : ValidationResult()
        object NoActiveSession : ValidationResult()
        object MissingCredential : ValidationResult()
        object InvalidCredential : ValidationResult()
        object Expired : ValidationResult()
        object IpRestricted : ValidationResult()
    }

    data class AccessRequest(
        val credential: String?,
        val clientIp: String?
    )

    @Volatile
    private var activeToken: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    @Volatile
    private var restrictionMode: ClientRestrictionMode = ClientRestrictionMode.NONE

    @Volatile
    private var serverIp: String? = null

    @Volatile
    private var pinnedClientIp: String? = null

    @Volatile
    private var sessionTtlMs: Long = 0L

    @Volatile
    private var serverPrefixLength: Int? = null

    fun startSession(
        ttlMs: Long,
        explicitToken: String? = null,
        serverIp: String?,
        restrictionMode: ClientRestrictionMode,
        serverPrefixLength: Int? = null
    ): SessionCredentials {
        val token = explicitToken ?: generateSessionToken()
        val expires = nowProvider() + ttlMs

        activeToken = token
        expiresAtMs = expires
        sessionTtlMs = ttlMs
        this.restrictionMode = restrictionMode
        this.serverIp = serverIp
        this.serverPrefixLength = serverPrefixLength
        pinnedClientIp = null

        return SessionCredentials(token = token, expiresAtMs = expires)
    }

    fun hasActiveSession(): Boolean {
        val token = activeToken ?: return false
        if (token.isBlank()) return false
        return nowProvider() < expiresAtMs
    }

    fun validate(request: AccessRequest): ValidationResult {
        val token = activeToken ?: return ValidationResult.NoActiveSession
        if (nowProvider() >= expiresAtMs) return ValidationResult.Expired

        val credential = request.credential
        if (credential.isNullOrBlank()) return ValidationResult.MissingCredential
        if (credential != token) return ValidationResult.InvalidCredential

        val clientIp = request.clientIp

        val result = when (restrictionMode) {
            ClientRestrictionMode.NONE -> ValidationResult.Allowed
            ClientRestrictionMode.SAME_SUBNET -> {
                if (clientIp.isNullOrBlank() || serverIp.isNullOrBlank()) {
                    ValidationResult.IpRestricted
                } else if (isSameSubnet(clientIp, serverIp!!, serverPrefixLength)) {
                    ValidationResult.Allowed
                } else {
                    ValidationResult.IpRestricted
                }
            }
            ClientRestrictionMode.FIRST_CLIENT -> {
                val incoming = clientIp ?: return ValidationResult.IpRestricted
                val pinned = pinnedClientIp
                if (pinned == null) {
                    pinnedClientIp = incoming
                    ValidationResult.Allowed
                } else if (pinned == incoming) {
                    ValidationResult.Allowed
                } else {
                    ValidationResult.IpRestricted
                }
            }
        }

        if (result is ValidationResult.Allowed) {
            touchSession()
        }
        return result
    }

    /**
     * Sliding expiration keep-alive for active sessions.
     */
    fun touchSession() {
        val ttl = sessionTtlMs
        if (ttl <= 0L) return
        if (activeToken == null) return
        expiresAtMs = nowProvider() + ttl
    }

    fun invalidateSession() {
        activeToken = null
        expiresAtMs = 0L
        sessionTtlMs = 0L
        pinnedClientIp = null
        serverIp = null
        serverPrefixLength = null
        restrictionMode = ClientRestrictionMode.NONE
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val secureRandom = SecureRandom()

        /**
         * URL-safe, high-entropy token suitable for query/header transport.
         */
        fun generateSessionToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            secureRandom.nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        private fun isSameSubnet(clientIp: String, serverIp: String, prefixLength: Int?): Boolean {
            val client = parseIpv4(clientIp) ?: return false
            val server = parseIpv4(serverIp) ?: return false

            val prefix = (prefixLength ?: 24).coerceIn(0, 32)
            val clientInt = ipv4ToInt(client)
            val serverInt = ipv4ToInt(server)
            val mask = when (prefix) {
                0 -> 0
                32 -> -1
                else -> -1 shl (32 - prefix)
            }
            return (clientInt and mask) == (serverInt and mask)
        }

        private fun parseIpv4(raw: String): ByteArray? {
            return try {
                val addr = InetAddress.getByName(raw)
                if (addr is Inet4Address) addr.address else null
            } catch (_: Exception) {
                null
            }
        }

        private fun ipv4ToInt(bytes: ByteArray): Int {
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }
    }
}

package com.example.uniremote.network

import com.example.uniremote.data.TvDevice
import kotlin.math.abs

/**
 * Protocol-agnostic TV controller interface.
 * Each brand provides its own implementation.
 *
 * Lifecycle:
 *   1. [connect]  — opens the transport (WebSocket / TLS / HTTP)
 *   2. [pair]     — optional; performs pairing handshake if needed (default: no-op → true)
 *   3. [sendKey]  — sends commands
 *   4. [getToken] — retrieves auth credential for persistence
 *   5. [disconnect]
 */
interface TvController {
    val device: TvDevice

    /** Opens the network connection (WebSocket / TCP / TLS). */
    suspend fun connect(): Boolean

    /**
     * Performs an explicit pairing handshake (PIN, popup approval, etc.).
     * Call this BEFORE [connect] for brands that require it.
     * Default implementation returns true immediately (no pairing needed).
     */
    suspend fun pair(): Boolean = true

    /** Closes the connection gracefully. */

    fun disconnect()

    /** Returns true if the current connection is open. */
    fun isConnected(): Boolean

    /** Sends a single key press. */
    suspend fun sendKey(key: TvKey)

    /** Types a string of text into the focused TV input field. */
    suspend fun sendText(text: String)

    /** Returns a list of installed app IDs and names. Empty list on failure. */
    suspend fun getInstalledApps(): List<TvApp>

    /** Launches the app with the given [appId]. */
    suspend fun launchApp(appId: String)

    /**
     * Returns the current auth token / client-key / PSK, or null if not yet paired.
     * [DeviceConnectionManager] calls this after [connect] to persist the credential.
     */
    fun getToken(): String? = device.token

    /**
     * Stores a token into the controller's internal state (no DataStore write).
     * Controllers that issue tokens should override this.
     */
    fun saveToken(token: String) {}

    /** Adjusts volume by [delta] steps (+1 = up, -1 = down). */
    suspend fun setVolume(delta: Int) {
        val key = if (delta > 0) TvKey.VOL_UP else TvKey.VOL_DOWN
        repeat(abs(delta)) {
            sendKey(key)
            kotlinx.coroutines.delay(150)
        }
    }

    /** Changes channel by [delta] steps. */
    suspend fun setChannel(delta: Int) {
        val key = if (delta > 0) TvKey.CH_UP else TvKey.CH_DOWN
        repeat(abs(delta)) {
            sendKey(key)
            kotlinx.coroutines.delay(150)
        }
    }

    /**
     * Moves the on-screen pointer by ([dx], [dy]) pixels.
     * Only meaningful for LG WebOS (pointer socket) and Android TV (ADB input).
     */
    suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("TV hiện tại không hỗ trợ chuột trực tiếp")
    }

    /** Sends a left-click / tap at the current pointer position. */
    suspend fun tapMouse() {
        throw UnsupportedOperationException("TV hiện tại không hỗ trợ chuột trực tiếp")
    }
}

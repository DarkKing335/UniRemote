package com.example.uniremote.network

import com.example.uniremote.data.TvDevice

/**
 * Protocol-agnostic TV controller interface.
 * Each brand provides its own implementation.
 */
interface TvController {
    val device: TvDevice

    /** Opens the network connection (WebSocket / TCP). */
    suspend fun connect(): Boolean

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

    /** Adjusts volume by [delta] steps (+1 = up, -1 = down). */
    suspend fun setVolume(delta: Int) {
        val key = if (delta > 0) TvKey.VOL_UP else TvKey.VOL_DOWN
        repeat(Math.abs(delta)) { sendKey(key) }
    }

    /** Changes channel by [delta] steps. */
    suspend fun setChannel(delta: Int) {
        val key = if (delta > 0) TvKey.CH_UP else TvKey.CH_DOWN
        repeat(Math.abs(delta)) { sendKey(key) }
    }

    /**
     * Moves the on-screen pointer by ([dx], [dy]) pixels.
     * Only meaningful for LG WebOS (pointer socket) and Android TV (ADB input).
     */
    suspend fun moveMouse(dx: Float, dy: Float) { /* optional */ }

    /** Sends a left-click / tap at the current pointer position. */
    suspend fun tapMouse() { /* optional */ }
}

package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Scanner

private const val TAG = "RokuTvController"

/**
 * Roku External Control Protocol (ECP) implementation.
 * Port: 8060
 * Documentation: https://developer.roku.com/docs/developer-program/debugging/external-control-api.md
 */
class RokuTvController(override val device: TvDevice) : TvController {

    private val baseUrl = "http://${device.ip}:${device.port}"

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Roku doesn't have a formal "connect" like WebSocket, just check heartbeat
            val url = URL("$baseUrl/query/device-info")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.requestMethod = "GET"
            val responseCode = conn.responseCode
            responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Roku at ${device.ip}", e)
            false
        }
    }

    override fun disconnect() {
        // No persistent connection to close
    }

    override fun isConnected(): Boolean = true // Stateless HTTP

    override suspend fun sendKey(key: TvKey) {
        val rokuKey = mapToRokuKey(key) ?: return
        post("/keypress/$rokuKey")
    }

    override suspend fun sendText(text: String) {
        text.forEach { char ->
            val encodedChar = if (char == ' ') "%20" else char.toString()
            post("/keypress/Lit_$encodedChar")
        }
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/query/apps")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            val scanner = Scanner(conn.inputStream).useDelimiter("\\A")
            val xml = if (scanner.hasNext()) scanner.next() else ""
            
            parseAppsXml(xml)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query Roku apps", e)
            emptyList()
        }
    }

    override suspend fun launchApp(appId: String) {
        post("/launch/$appId")
    }

    private suspend fun post(path: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            val code = conn.responseCode
            Log.d(TAG, "POST $path returned $code")
        } catch (e: Exception) {
            Log.e(TAG, "Error POSTing to Roku: $path", e)
        }
    }

    private fun mapToRokuKey(key: TvKey): String? = when (key) {
        TvKey.UP      -> "Up"
        TvKey.DOWN    -> "Down"
        TvKey.LEFT    -> "Left"
        TvKey.RIGHT   -> "Right"
        TvKey.OK      -> "Select"
        TvKey.BACK    -> "Back"
        TvKey.HOME    -> "Home"
        TvKey.POWER   -> "Power" // Note: only works on some Roku devices
        TvKey.VOL_UP  -> "VolumeUp"
        TvKey.VOL_DOWN -> "VolumeDown"
        TvKey.MUTE    -> "VolumeMute"
        TvKey.PLAY    -> "Play"
        TvKey.PAUSE   -> "Play" // Roku uses Play for both
        TvKey.STOP    -> "Stop"
        TvKey.FF      -> "Fwd"
        TvKey.RW      -> "Rev"
        TvKey.NEXT    -> "Fwd"
        TvKey.PREV    -> "Rev"
        TvKey.SETTINGS -> "Info" // Approx mapping
        TvKey.SEARCH  -> "Search"
        else -> null
    }

    private fun parseAppsXml(xml: String): List<TvApp> {
        val apps = mutableListOf<TvApp>()
        // Simple regex parsing of <app id="...">(...)</app>
        val regex = "<app id=\"(\\d+)\"[^>]*>([^<]+)</app>".toRegex()
        regex.findAll(xml).forEach { match ->
            val id = match.groupValues[1]
            val name = match.groupValues[2]
            apps.add(TvApp(id = id, name = name, iconUrl = "$baseUrl/query/icon/$id"))
        }
        return apps
    }
}

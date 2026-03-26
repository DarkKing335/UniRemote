package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "RokuController"

/**
 * Roku TV controller using the External Control REST API (ECP).
 * API Documentation: https://developer.roku.com/docs/developer-program/debugging/external-control-api.md
 * 
 * Commands are sent via HTTP POST to: http://<ip>:8060/keypress/<Command>
 */
class RokuController(override val device: TvDevice) : TvController {

    private val client = NetworkClient.instance

    private val baseUrl = "http://${device.ip}:${device.port}"
    private var isConnected = false

    companion object {
        private val KEY_MAP = mapOf(
            TvKey.UP       to "Up",
            TvKey.DOWN     to "Down",
            TvKey.LEFT     to "Left",
            TvKey.RIGHT    to "Right",
            TvKey.OK       to "Select",
            TvKey.BACK     to "Back",
            TvKey.HOME     to "Home",
            TvKey.VOL_UP   to "VolumeUp",
            TvKey.VOL_DOWN to "VolumeDown",
            TvKey.MUTE     to "VolumeMute",
            TvKey.CH_UP    to "ChannelUp",
            TvKey.CH_DOWN  to "ChannelDown",
            TvKey.POWER    to "Power",
            TvKey.PLAY     to "Play",
            TvKey.PAUSE    to "Play",    // Roku toggles play/pause with 'Play'
            TvKey.STOP     to "Stop",
            TvKey.FF       to "Fwd",
            TvKey.RW       to "Rev",
            TvKey.INFO     to "Info",
            TvKey.SEARCH   to "Search",
            TvKey.BACK     to "Back",
            TvKey.MENU     to "Info"     // Roku typically uses info or star (*)
        )
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Roku doesn't maintain a persistent connection, but we can ping the root or query/device-info
        // to verify it's reachable.
        runCatching {
            val request = Request.Builder().url("$baseUrl/query/device-info").build()
            client.newCall(request).execute().use { response ->
                isConnected = response.isSuccessful
                isConnected
            }
        }.getOrElse { 
            isConnected = false
            false 
        }
    }

    override fun disconnect() {
        // No persistent connection to close
        isConnected = false
    }

    override fun isConnected(): Boolean = isConnected

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val rokuKey = KEY_MAP[key] ?: return@withContext
        post("$baseUrl/keypress/$rokuKey")
    }

    override suspend fun sendText(text: String): Unit = withContext(Dispatchers.IO) {
        // Roku accepts keys via Lit_ prefix for characters.
        // Spaces are handled via "Lit_%20"
        for (char in text) {
            val endpoint = when (char) {
                '\n' -> "Enter"
                '\r' -> "Enter"
                '\b' -> "Backspace"
                ' '  -> "Lit_%20"
                else -> "Lit_${java.net.URLEncoder.encode(char.toString(), "UTF-8")}"
            }
            post("$baseUrl/keypress/$endpoint")
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<TvApp>()
        runCatching {
            val request = Request.Builder().url("$baseUrl/query/apps").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val xml = response.body?.string() ?: return@use
                
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(xml.reader())
                
                var eventType = parser.eventType
                var appId = ""
                var appName = ""
                
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            if (parser.name == "app") {
                                appId = parser.getAttributeValue(null, "id") ?: ""
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            if (appId.isNotEmpty()) {
                                appName = parser.text.trim()
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (parser.name == "app" && appId.isNotEmpty()) {
                                apps.add(TvApp(id = appId, name = appName))
                                appId = ""
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
        apps
    }

    override suspend fun launchApp(appId: String): Unit = withContext(Dispatchers.IO) {
        post("$baseUrl/launch/$appId")
    }

    private suspend fun post(url: String) {
        kotlin.runCatching {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody())
                    .build()
                val call = client.newCall(request)
                
                cont.invokeOnCancellation { call.cancel() }
                
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
        }.onFailure {
            Log.e(TAG, "POST failed for $url: ${it.message}")
            isConnected = false
        }
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("Roku TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }

    override suspend fun tapMouse() {
        throw UnsupportedOperationException("Roku TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }
}

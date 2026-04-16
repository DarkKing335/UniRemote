package com.example.uniremote.network

import android.util.Base64
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Samsung Smart TV controller using the WebSocket remote control API.
 * Tested with: Tizen-based TVs (2016+), QLED, Neo QLED, Frame.
 *
 * Protocol docs: https://github.com/Toxblh/samsung-tv-control
 */
class SamsungTvController(
    override val device: TvDevice,
    private val onTokenReceived: (String) -> Unit = {},
    private val onUnexpectedDisconnect: (String) -> Unit = {}
) : TvController {

    companion object {
        private const val APP_NAME = "UniRemote"
        // Map from TvKey to Samsung KEY_ string
        private val KEY_MAP = mapOf(
            TvKey.UP         to "KEY_UP",
            TvKey.DOWN       to "KEY_DOWN",
            TvKey.LEFT       to "KEY_LEFT",
            TvKey.RIGHT      to "KEY_RIGHT",
            TvKey.OK         to "KEY_ENTER",
            TvKey.BACK       to "KEY_RETURN",
            TvKey.HOME       to "KEY_HOME",
            TvKey.MENU       to "KEY_MENU",
            TvKey.EXIT       to "KEY_EXIT",
            TvKey.VOL_UP     to "KEY_VOLUP",
            TvKey.VOL_DOWN   to "KEY_VOLDOWN",
            TvKey.MUTE       to "KEY_MUTE",
            TvKey.CH_UP      to "KEY_CHUP",
            TvKey.CH_DOWN    to "KEY_CHDOWN",
            TvKey.POWER      to "KEY_POWER",
            TvKey.RED        to "KEY_RED",
            TvKey.GREEN      to "KEY_GREEN",
            TvKey.YELLOW     to "KEY_YELLOW",
            TvKey.BLUE       to "KEY_BLUE",
            TvKey.PLAY       to "KEY_PLAY",
            TvKey.PAUSE      to "KEY_PAUSE",
            TvKey.STOP       to "KEY_STOP",
            TvKey.FF         to "KEY_FF",
            TvKey.RW         to "KEY_REWIND",
            TvKey.NEXT       to "KEY_NEXT",
            TvKey.PREV       to "KEY_PREV",
            TvKey.NUM_0      to "KEY_0",
            TvKey.NUM_1      to "KEY_1",
            TvKey.NUM_2      to "KEY_2",
            TvKey.NUM_3      to "KEY_3",
            TvKey.NUM_4      to "KEY_4",
            TvKey.NUM_5      to "KEY_5",
            TvKey.NUM_6      to "KEY_6",
            TvKey.NUM_7      to "KEY_7",
            TvKey.NUM_8      to "KEY_8",
            TvKey.NUM_9      to "KEY_9",
            TvKey.SOURCE     to "KEY_SOURCE",
            TvKey.HDMI_1     to "KEY_HDMI1",
            TvKey.HDMI_2     to "KEY_HDMI2",
            TvKey.HDMI_3     to "KEY_HDMI3",
            TvKey.HDMI_4     to "KEY_HDMI4",
            TvKey.INFO       to "KEY_INFO",
            TvKey.GUIDE      to "KEY_GUIDE",
            TvKey.SETTINGS   to "KEY_TOOLS",
            TvKey.SEARCH     to "KEY_SEARCH",
            TvKey.NETFLIX    to "KEY_NETFLIX",
            TvKey.YOUTUBE    to "KEY_YOUTUBE",
            TvKey.SAMSUNG_SMART_HUB to "KEY_SMART",
            TvKey.ASPECT_RATIO      to "KEY_ASPECT",
            TvKey.PIC_MODE          to "KEY_PICTURE_SIZE",
            TvKey.SLEEP             to "KEY_SLEEP",
        )
    }

    private val client = NetworkClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // Keep-alive: Samsung drops idle WS connections after ~60s
        .build()

    private var webSocket: WebSocket? = null
    // @Volatile: written from OkHttp's websocket thread, read from coroutine threads
    @Volatile private var connected = false
    @Volatile private var manualCloseRequested = false
    // Holds the most recent token (may differ from device.token if TV issued a new one)
    @Volatile private var liveToken: String? = device.token

    private val appNameB64: String
        get() = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)

    private fun canUseInsecureSamsungProtocol(): Boolean {
        return TransportSecurityPolicy.allowInsecureDeviceProtocol(
            "Samsung Remote API over ws/http (non-TLS)",
            host = device.ip
        )
    }

    private fun samsungRestBaseUrl(): String? {
        return if (device.port == 8002) {
            "https://${device.ip}:${device.port}"
        } else if (canUseInsecureSamsungProtocol()) {
            "http://${device.ip}:${device.port}"
        } else {
            null
        }
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        manualCloseRequested = false
        val secureTransport = device.port == 8002
        if (!secureTransport && !canUseInsecureSamsungProtocol()) {
            return@withContext false
        }

        val scheme = if (secureTransport) "wss" else "ws"
        // Samsung protocol: ?name=<b64> is required for identification.
        // Additionally, token is passed as &token=<token> for pre-2018 Tizen models
        // that don't read the Authorization header. Both are sent simultaneously;
        // newer models prefer the header, older models prefer the query param.
        val tokenSuffix = if (!liveToken.isNullOrEmpty()) "&token=$liveToken" else ""
        val url = "$scheme://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control" +
                  "?name=$appNameB64$tokenSuffix"
        val requestBuilder = Request.Builder().url(url)
        if (!liveToken.isNullOrEmpty()) {
            // Also send via header for newer Tizen models (≥2018) that prefer it over query param.
            requestBuilder.addHeader("Authorization", "Bearer $liveToken")
        }
        val request = requestBuilder.build()
        val deferred = CompletableDeferred<Boolean>()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                if (!deferred.isCompleted) deferred.complete(true)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Handle auth challenge if TV requires pairing
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val event = json.optString("event")
                if (event == "ms.channel.connect") {
                    val data = json.optJSONObject("data")
                    // Token may be in data.token (newer models) or
                    // data.clients[].attributes.token (older models — Legaccy SmartTV API)
                    var token = data?.optString("token").takeIf { !it.isNullOrEmpty() }
                    if (token == null) {
                        val clients = data?.optJSONArray("clients")
                        if (clients != null) {
                            for (i in 0 until clients.length()) {
                                val attr = clients.getJSONObject(i).optJSONObject("attributes")
                                val name = attr?.optString("name")
                                if (name == appNameB64) {
                                    token = attr?.optString("token").takeIf { !it.isNullOrEmpty() }
                                    break
                                }
                            }
                        }
                    }
                    if (!token.isNullOrEmpty() && token != liveToken) {
                        liveToken = token
                        onTokenReceived(token)
                    }
                    connected = true
                    if (!deferred.isCompleted) deferred.complete(true)
                } else if (event == "ms.channel.unauthorized") {
                    if (!deferred.isCompleted) deferred.complete(false)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasConnected = connected
                connected = false
                if (wasConnected && !manualCloseRequested) {
                    onUnexpectedDisconnect("Samsung WebSocket failure: ${t.message ?: "unknown"}")
                }
                manualCloseRequested = false
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasConnected = connected
                connected = false
                if (wasConnected && !manualCloseRequested) {
                    onUnexpectedDisconnect("Samsung WebSocket closed: $reason")
                }
                manualCloseRequested = false
            }
        })

        // Timeout to prevent infinite hang if TV ignores prompt
        val result = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            deferred.await()
        } ?: false

        if (!result) {
            webSocket?.cancel()
            disconnect()
        }
        result
    }

    override fun disconnect() {
        manualCloseRequested = true
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connected = false
    }

    override fun isConnected(): Boolean = connected

    /** Returns the most recent token issued by the TV (may differ from device.token). */
    override fun getToken(): String? = liveToken

    override fun saveToken(token: String) { liveToken = token }

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val samsungKey = KEY_MAP[key] ?: return@withContext
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", samsungKey)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        webSocket?.send(payload.toString())
    }

    override suspend fun sendText(text: String): Unit = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext

        // Samsung Tizen text input protocol:
        // Cmd=<base64(utf8 text)>, DataOfCmd="base64", Option="false", TypeOfRemote="SendInputString"
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val inputPayload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", encoded)
                put("DataOfCmd", "base64")
                put("Option", "false")
                put("TypeOfRemote", "SendInputString")
            })
        }
        webSocket?.send(inputPayload.toString())
        kotlinx.coroutines.delay(45)

        val endPayload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "")
                put("DataOfCmd", "")
                put("Option", "false")
                put("TypeOfRemote", "SendInputEnd")
            })
        }
        webSocket?.send(endPayload.toString())
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = samsungRestBaseUrl() ?: return@withContext emptyList()
            val url = "$baseUrl/api/v2/applications"
            val request = Request.Builder().url(url).build()
            // Use .use{} to guarantee the response body is always closed, preventing connection leaks
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList()
                val json  = JSONObject(body)
                val data  = json.getJSONArray("data")
                (0 until data.length()).map { i ->
                    val app = data.getJSONObject(i)
                    val iconRaw = app.optString("iconURI").takeIf { it.isNotEmpty() }
                    TvApp(
                        id      = app.optString("appId"),
                        name    = app.optString("name"),
                        iconUrl = normalizeIconUrl(baseUrl, iconRaw)
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun normalizeIconUrl(baseUrl: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return if (value.startsWith("/")) "$baseUrl$value" else "$baseUrl/$value"
    }

    override suspend fun launchApp(appId: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = samsungRestBaseUrl() ?: return@withContext
            val url = "$baseUrl/api/v2/applications/$appId"
            val body = ByteArray(0).toRequestBody()
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().close()
        }
    }

    /**
     * Samsung Tizen WebSocket does not support mouse pointer control.
     * Throws UnsupportedOperationException so the ViewModel can surface a user-facing message.
     */
    override suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("Samsung TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }

    override suspend fun tapMouse() {
        throw UnsupportedOperationException("Samsung TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }
}

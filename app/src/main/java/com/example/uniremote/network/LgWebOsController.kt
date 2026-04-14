package com.example.uniremote.network

import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG WebOS TV controller using the SSAP (Simple Service Access Protocol) over WebSocket.
 * Tested with: LG WebOS 3.0+ (2016+), OLED, QNED, NanoCell.
 *
 * Protocol: ws://ip:3000
 * Reference: https://github.com/ConnectSDK/Connect-SDK-Android-Core
 */
class LgWebOsController(
    override val device: TvDevice,
    private val onTokenReceived: (String) -> Unit = {},
    private val onUnexpectedDisconnect: (String) -> Unit = {}
) : TvController {

    companion object {
        private val KEY_MAP = mapOf(
            TvKey.UP       to "UP",
            TvKey.DOWN     to "DOWN",
            TvKey.LEFT     to "LEFT",
            TvKey.RIGHT    to "RIGHT",
            TvKey.OK       to "ENTER",
            TvKey.BACK     to "BACK",
            TvKey.HOME     to "HOME",
            TvKey.MENU     to "MENU",
            TvKey.EXIT     to "EXIT",
            TvKey.VOL_UP   to "VOLUMEUP",
            TvKey.VOL_DOWN to "VOLUMEDOWN",
            TvKey.MUTE     to "MUTE",
            TvKey.CH_UP    to "CHANNELUP",
            TvKey.CH_DOWN  to "CHANNELDOWN",
            TvKey.POWER    to "POWER",
            TvKey.RED      to "RED",
            TvKey.GREEN    to "GREEN",
            TvKey.YELLOW   to "YELLOW",
            TvKey.BLUE     to "BLUE",
            TvKey.PLAY     to "PLAY",
            TvKey.PAUSE    to "PAUSE",
            TvKey.STOP     to "STOP",
            TvKey.FF       to "FASTFORWARD",
            TvKey.RW       to "REWIND",
            TvKey.NUM_0    to "0",
            TvKey.NUM_1    to "1",
            TvKey.NUM_2    to "2",
            TvKey.NUM_3    to "3",
            TvKey.NUM_4    to "4",
            TvKey.NUM_5    to "5",
            TvKey.NUM_6    to "6",
            TvKey.NUM_7    to "7",
            TvKey.NUM_8    to "8",
            TvKey.NUM_9    to "9",
            TvKey.INFO     to "INFO",
            TvKey.GUIDE    to "GUIDE",
            TvKey.SETTINGS to "SETTINGS",
            TvKey.SOURCE   to "EXTERNAL_INPUT",
            TvKey.SEARCH   to "SEARCH",
        )
    }

    private val client = NetworkClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // LG WebOS drops idle WS after 60s
        .build()

    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    // @Volatile: written from OkHttp's websocket callback thread, read from coroutine threads
    @Volatile private var connected = false
    @Volatile private var manualCloseRequested = false
    // Live token — may differ from device.token if TV issued a new key this session
    @Volatile private var liveToken: String? = device.token
    private val msgId = AtomicInteger(0)
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    // Mutex prevents two concurrent moveMouse() calls from opening two pointer sockets at 60fps
    private val pointerMutex = Mutex()

    // ── Registration payload ──────────────────────────────────────────────────
    private fun buildRegistration() = JSONObject().apply {
        put("type", "register")
        put("id", "register_0")
        put("payload", JSONObject().apply {
            put("client-key", device.token ?: "")
            put("pairingType", "PROMPT")
            put("manifest", JSONObject().apply {
                put("manifestVersion", 1)
                put("appId", "UniRemote")
                put("vendor", "Open-Source")
                put("localizedAppNames", JSONObject().apply { put("", "UniRemote") })
                put("permissions", JSONArray(
                    listOf("LAUNCH","LAUNCH_WEBAPP","APP_TO_APP","CLOSE","TEST_OPEN",
                           "TEST_PROTECTED","CONTROL_AUDIO","CONTROL_DISPLAY",
                           "CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_RECORDING",
                           "CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV",
                           "CONTROL_POWER","READ_APP_STATUS","READ_CURRENT_CHANNEL",
                           "READ_INPUT_DEVICE_LIST","READ_NETWORK_STATE",
                           "READ_RUNNING_APPS","READ_TV_CHANNEL_LIST",
                           "WRITE_NOTIFICATION_TOAST","READ_POWER_STATE","READ_COUNTRY_INFO")
                ))
            })
        })
    }.toString()

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        manualCloseRequested = false
        if (!TransportSecurityPolicy.allowInsecureDeviceProtocol("LG WebOS SSAP over ws://", host = device.ip)) {
            return@withContext false
        }
        val url = "ws://${device.ip}:${device.port}"
        val requestBuilder = Request.Builder().url(url)
        if (!device.token.isNullOrEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer ${device.token}")
        }
        val request = requestBuilder.build()
        val deferred = CompletableDeferred<Boolean>()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildRegistration())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = json.optString("type")
                val id   = json.optString("id")

                when (type) {
                    "registered" -> {
                        // Pairing accepted – extract client key if provided
                        val key = json.optJSONObject("payload")?.optString("client-key")
                        if (!key.isNullOrEmpty() && key != liveToken) {
                            liveToken = key
                            onTokenReceived(key)
                        }
                        connected = true
                        if (!deferred.isCompleted) deferred.complete(true)
                    }
                    "error" -> {
                        if (id == "register_0" && !deferred.isCompleted) {
                            deferred.complete(false)
                        }
                        pendingRequests[id]?.completeExceptionally(IOException("SSAP error: ${json.optString("error")}"))
                    }
                    "response" -> {
                        pendingRequests[id]?.complete(json)
                    }
                    "prompt" -> { /* TV is showing the pairing dialog */ }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasConnected = connected
                connected = false
                if (wasConnected && !manualCloseRequested) {
                    onUnexpectedDisconnect("LG WebOS WebSocket failure: ${t.message ?: "unknown"}")
                }
                manualCloseRequested = false
                clearPendingRequests()
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasConnected = connected
                connected = false
                if (wasConnected && !manualCloseRequested) {
                    onUnexpectedDisconnect("LG WebOS WebSocket closed: $reason")
                }
                manualCloseRequested = false
                clearPendingRequests()
            }
        })
        try {
            kotlinx.coroutines.withTimeout(8_000) {
                deferred.await()
            }
        } catch (e: Exception) {
            webSocket?.cancel()
            disconnect()
            false
        }
    }

    override fun disconnect() {
        manualCloseRequested = true
        pointerSocket?.close(1000, null)
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connected = false
        clearPendingRequests()
    }

    override fun isConnected() = connected

    override fun getToken(): String? = liveToken
    override fun saveToken(token: String) { liveToken = token }

    private fun clearPendingRequests() {
        // Complete with an exception rather than cancelling — callers wrapped in runCatching
        // will get a tidy IOException rather than a CancellationException that could propagate.
        pendingRequests.values.forEach {
            it.completeExceptionally(IOException("WebSocket disconnected"))
        }
        pendingRequests.clear()
    }

    // ── Send an SSAP request and await response ────────────────────────────────
    private suspend fun request(uri: String, payload: JSONObject? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            val id = "req_${msgId.incrementAndGet()}"
            val message = JSONObject().apply {
                put("type", "request")
                put("id", id)
                put("uri", uri)
                payload?.let { put("payload", it) }
            }
            val deferred = CompletableDeferred<JSONObject>()
            pendingRequests[id] = deferred
            webSocket?.send(message.toString())
            runCatching {
                kotlinx.coroutines.withTimeout(5_000L) { deferred.await() }
            }.getOrNull().also { pendingRequests.remove(id) }
        }

    override suspend fun sendKey(key: TvKey) {
        // LG WebOS: most keys use the IME key-event API, but some have dedicated SSAP endpoints
        // that are more reliable (media controls, power-off).
        when (key) {
            // ── Dedicated SSAP endpoints (more reliable than IME) ────────────────
            TvKey.POWER  -> request("ssap://system/turnOff")
            TvKey.PLAY   -> request("ssap://media.controls/play")
            TvKey.PAUSE  -> request("ssap://media.controls/pause")
            TvKey.STOP   -> request("ssap://media.controls/stop")
            TvKey.FF     -> request("ssap://media.controls/fastForward")
            TvKey.RW     -> request("ssap://media.controls/rewind")
            TvKey.VOL_UP -> request("ssap://audio/volumeUp")
            TvKey.VOL_DOWN -> request("ssap://audio/volumeDown")
            TvKey.MUTE   -> request("ssap://audio/setMute", JSONObject().put("mute", true))
            TvKey.CH_UP  -> request("ssap://tv/channelUp")
            TvKey.CH_DOWN -> request("ssap://tv/channelDown")
            // ── All other keys go through IME key-event ──────────────────────────
            else -> {
                val lgKey = KEY_MAP[key]
                    ?: throw UnsupportedOperationException("LG WebOS does not support key: $key")
                request(
                    uri = "ssap://com.webos.service.ime/sendKeyEvent",
                    payload = JSONObject().apply {
                        put("keyCode", lgKey)
                        put("type", "Standard")
                    }
                )
            }
        }
    }

    override suspend fun sendText(text: String) {
        request(
            uri = "ssap://com.webos.service.ime/insertText",
            payload = JSONObject().apply {
                put("text", text)
                put("replace", 0)
            }
        )
    }

    override suspend fun getInstalledApps(): List<TvApp> {
        val resp = request("ssap://com.webos.applicationManager/listApps") ?: return emptyList()
        val apps = resp.optJSONObject("payload")?.optJSONArray("apps") ?: return emptyList()
        val baseUrl = "http://${device.ip}:${device.port}"
        return (0 until apps.length()).map { i ->
            val app = apps.getJSONObject(i)
            val iconRaw = app.optString("icon").takeIf { it.isNotEmpty() }
            TvApp(
                id      = app.optString("id"),
                name    = app.optString("title"),
                iconUrl = normalizeIconUrl(baseUrl, iconRaw)
            )
        }
    }

    private fun normalizeIconUrl(baseUrl: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return if (value.startsWith("/")) "$baseUrl$value" else "$baseUrl/$value"
    }

    override suspend fun launchApp(appId: String) {
        request(
            uri = "ssap://system.launcher/launch",
            payload = JSONObject().apply { put("id", appId) }
        )
    }

    // ── Mouse pointer via separate WebSocket ──────────────────────────────────
    private var pointerSocketDeferred: CompletableDeferred<Boolean>? = null
    private suspend fun ensurePointerSocket() = pointerMutex.withLock {
        if (pointerSocket != null) return@withLock
        val resp = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val socketPath = resp?.optJSONObject("payload")?.optString("socketPath") ?: return@withLock

        pointerSocketDeferred = CompletableDeferred()
        val req = Request.Builder().url(socketPath).build()
        pointerSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                pointerSocketDeferred?.complete(true)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                pointerSocket = null
            }
        })
        val ok = kotlinx.coroutines.withTimeoutOrNull(3000L) { pointerSocketDeferred?.await() }
        if (ok != true) throw UnsupportedOperationException("Chưa thể kích hoạt chuột trên TV này. Hãy thử lại.")
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        withContext(Dispatchers.IO) {
            ensurePointerSocket()
            val payload = "type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\ndown:0\n\n"
            pointerSocket?.send(payload)
        }
    }

    override suspend fun tapMouse() {
        withContext(Dispatchers.IO) {
            ensurePointerSocket()
            pointerSocket?.send("type:click\n\n")
        }
    }
}

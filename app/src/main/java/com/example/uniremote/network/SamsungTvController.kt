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
    private val onTokenReceived: (String) -> Unit = {}
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
        .build()

    private var webSocket: WebSocket? = null
    // @Volatile: written from OkHttp's websocket thread, read from coroutine threads
    @Volatile private var connected = false

    private val appNameB64: String
        get() = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val scheme = if (device.port == 8002) "wss" else "ws"
        val tokenParam = if (!device.token.isNullOrEmpty()) "&token=${device.token}" else ""
        val url = "$scheme://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control" +
                  "?name=$appNameB64$tokenParam"
        val request = Request.Builder().url(url).build()
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
                    val token = data?.optString("token")
                    if (!token.isNullOrEmpty() && token != device.token) {
                        onTokenReceived(token)
                    }
                    connected = true
                    if (!deferred.isCompleted) deferred.complete(true)
                } else if (event == "ms.channel.unauthorized") {
                    if (!deferred.isCompleted) deferred.complete(false)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (!deferred.isCompleted) deferred.complete(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
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
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connected = false
    }

    override fun isConnected(): Boolean = connected

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
        // Samsung Tizen text input protocol:
        //   Cmd        = "SendInputString"  (the action)
        //   DataOfCmd  = <text to type>     (the data)
        // Note: the previous code had Cmd and DataOfCmd swapped — this is the correct order.
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "SendInputString")
                put("DataOfCmd", text)
                put("TypeOfRemote", "SendInputEnd")
            })
        }
        webSocket?.send(payload.toString())
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://${device.ip}:${device.port}/api/v2/applications"
            val request = Request.Builder().url(url).build()
            // Use .use{} to guarantee the response body is always closed, preventing connection leaks
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList()
                val json  = JSONObject(body)
                val data  = json.getJSONArray("data")
                (0 until data.length()).map { i ->
                    val app = data.getJSONObject(i)
                    TvApp(
                        id      = app.optString("appId"),
                        name    = app.optString("name"),
                        iconUrl = app.optString("iconURI").takeIf { it.isNotEmpty() }
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun launchApp(appId: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://${device.ip}:${device.port}/api/v2/applications/$appId"
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

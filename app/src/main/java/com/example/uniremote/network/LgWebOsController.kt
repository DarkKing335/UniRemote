package com.example.uniremote.network

import android.util.Log
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG WebOS TV controller using the SSAP (Simple Service Access Protocol) over WebSocket.
 * Tested with: LG WebOS 3.0+ (2016+), OLED, QNED, NanoCell.
 *
 * Protocol: wss://ip:3001  (ws://ip:3000 for older models)
 * Reference: WebOSTVServiceSocketClient.java (decompiled from APK)
 *
 * Connection flow (per APK WebOSTVServiceSocketClient):
 *   1. WS onOpen → send "hello" message  (helloTV())
 *   2. TV responds with type="hello"     → send "register" with client-key + manifest
 *   3. TV responds with type="response"  → pairingType → show prompt if needed, state=REGISTERING
 *   4. TV responds with type="registered"→ extract client-key, sendVerification(), state=REGISTERED
 *   5. Drain queued commands, notify onConnect()
 */
class LgWebOsController(
    override val device: TvDevice,
    private val onTokenReceived: (String) -> Unit = {},
    private val onUnexpectedDisconnect: (String) -> Unit = {},
    // Callback to signal PIN pairing stages to the ViewModel (mirrors GoogleTvController pattern)
    private val onPairingState: (PairingState) -> Unit = {}
) : TvController {

    // ── State machine (mirrors WebOSTVServiceSocketClient.State) ────────────────
    private enum class State { IDLE, CONNECTING, REGISTERING, REGISTERED }

    companion object {
        private const val TAG = "LgWebOsCtrl"

        // Keys that the APK routes through the mouse pointer socket (sendSpecialKey / ok)
        // rather than the IME key-event path. Using mouse socket is more reliable for these.
        private val POINTER_SOCKET_KEYS = setOf(
            TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT,
            TvKey.OK, TvKey.HOME, TvKey.BACK
        )

        // Mouse socket button name map (WebOSTVMouseSocketConnection.button())
        private val POINTER_BUTTON_MAP = mapOf(
            TvKey.UP    to "UP",
            TvKey.DOWN  to "DOWN",
            TvKey.LEFT  to "LEFT",
            TvKey.RIGHT to "RIGHT",
            TvKey.OK    to "ENTER",
            TvKey.HOME  to "HOME",
            TvKey.BACK  to "BACK"
        )

        // Keys handled via dedicated SSAP URIs (more reliable than IME)
        private val SSAP_KEY_MAP = mapOf(
            TvKey.POWER    to "ssap://system/turnOff",
            TvKey.PLAY     to "ssap://media.controls/play",
            TvKey.PAUSE    to "ssap://media.controls/pause",
            TvKey.STOP     to "ssap://media.controls/stop",
            TvKey.FF       to "ssap://media.controls/fastForward",
            TvKey.RW       to "ssap://media.controls/rewind",
            TvKey.VOL_UP   to "ssap://audio/volumeUp",
            TvKey.VOL_DOWN to "ssap://audio/volumeDown",
            TvKey.CH_UP    to "ssap://tv/channelUp",
            TvKey.CH_DOWN  to "ssap://tv/channelDown"
        )

        // Special keys are sent via pointer socket (ConnectSDK sendSpecialKey pattern).
        private val IME_KEY_MAP = mapOf(
            TvKey.MENU     to "MENU",
            TvKey.EXIT     to "EXIT",
            TvKey.MUTE     to "MUTE",
            TvKey.RED      to "RED",
            TvKey.GREEN    to "GREEN",
            TvKey.YELLOW   to "YELLOW",
            TvKey.BLUE     to "BLUE",
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
            TvKey.SEARCH   to "SEARCH"
        )
    }

    // OkHttp client: 5s connect, 30s read, 30s ping (LG drops idle WS after ~60s)
    private val client = NetworkClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // Some LG models return pointer socket as wss:// with self-signed certs.
    // Keep trust relaxation scoped to pointer socket only (LAN device control path).
    private val pointerTlsClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

        client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // Some LG models require secure SSAP over wss://3001 and use self-signed certs.
    private val ssapTlsClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

        client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null

    // @Volatile: written from OkHttp callback thread, read from coroutine threads
    @Volatile private var state: State = State.IDLE
    // mConnectSucceeded: mirrors APK — true after first onOpen(); prevents double-firing
    // handleConnectError when onError fires after onClose during teardown.
    @Volatile private var mConnectSucceeded = false
    @Volatile private var manualCloseRequested = false
    // Live token — may differ from device.token if TV issued a new key this session
    @Volatile private var liveToken: String? = device.token

    private val msgId = AtomicInteger(0)
    // Pending SSAP request completions keyed by message id
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    // URI by request id, used for actionable logging when TV returns SSAP errors.
    private val pendingRequestUris = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Command queue — accumulates Strings to send while state < REGISTERED (APK: commandQueue)
    private val commandQueue = ArrayDeque<String>()
    private val commandQueueLock = Any()

    // Pointer socket deferred + mutex guard (prevents two concurrent moveMouse calls
    // from opening two pointer sockets simultaneously)
    private val pointerMutex = Mutex()
    private var pointerSocketDeferred: CompletableDeferred<Boolean>? = null

    // Held by connect() — lets us signal the caller when registration completes or fails.
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    // PIN channel — collects the PIN entered by the user after TV shows the PIN dialog
    // APK: sendPairingKey(str) → ssap://pairing/setPin {pin: str}
    private var pinChannel: CompletableDeferred<String?> = CompletableDeferred()

    // ── Outgoing message builders ─────────────────────────────────────────────

    /**
     * Step 1 of the LG handshake (APK: helloTV()).
     * Sends device metadata so the TV can identify the client before showing a dialog.
     */
    private fun buildHello(): String = JSONObject().apply {
        put("type", "hello")
        put("id", msgId.incrementAndGet())
        put("payload", JSONObject().apply {
            put("sdkVersion", "2.4.0")
            put("deviceModel",  android.os.Build.MODEL)
            put("OSVersion",    android.os.Build.VERSION.SDK_INT.toString())
            put("appId",        "com.uniremote.app")
            put("appName",      "UniRemote")
            put("appRegion",    "")
        })
    }.toString()

    /**
     * Step 2 of the LG handshake (APK: sendRegister()).
     * Sends the client-key (if known) and the permissions manifest.
     *
     * APK evidence (WebOSTVServiceSocketClient.java line 548-553):
     *   if (mconfig.getClientKey() != null) payload.put("client-key", clientKey);
     *   if (PairingType.PIN_CODE.equals(mPairingType)) payload.put("pairingType", WEBOS_PAIRING_PIN);
     *
     * APK evidence (WebOSTVService.java line 271):
     *   pairingType = DeviceService.PairingType.PIN_CODE;  // Always PIN_CODE for LG
     *
     * Using PIN mode means:
     *   - TV displays an 8-digit PIN on screen
     *   - App collects PIN from user, sends ssap://pairing/setPin
     *   - TV sends "registered" + client-key → full control granted
     * PROMPT mode only shows Allow/Deny, which gives limited/no control on many LG firmware versions.
     */
    private fun buildRegistration(): String = JSONObject().apply {
        put("type", "register")
        put("id", msgId.incrementAndGet())
        put("payload", JSONObject().apply {
            // Attach stored client-key so TV skips the pairing dialog on re-connect
            val key = liveToken
            if (!key.isNullOrEmpty()) put("client-key", key)
            // CRITICAL: Must use PIN mode (not PROMPT) to get full control
            // APK: WEBOS_PAIRING_PIN = "PIN"
            put("pairingType", "PIN")
            put("manifest", JSONObject().apply {
                put("manifestVersion", 1)
                // Keep permissions aligned with ConnectSDK defaults (open + protected + personal-activity).
                put("permissions", JSONArray(
                    listOf(
                        // Open permissions
                        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP",
                        "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK", "UPDATE_FROM_REMOTE_APP",
                        // Protected permissions
                        "CONTROL_POWER", "READ_INSTALLED_APPS", "CONTROL_DISPLAY",
                        "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
                        "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
                        "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
                        "CONTROL_BLUETOOTH", "CHECK_BLUETOOTH_DEVICE",
                        "CONTROL_USER_INFO", "CONTROL_TIMER_INFO",
                        "READ_SETTINGS", "CONTROL_TV_SCREEN",
                        // Personal activity permissions (required for keyboard + pointer control)
                        "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
                        "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS"
                    )
                ))
            })
        })
    }.toString()

    // ── TvController: connect ─────────────────────────────────────────────────

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Guard: APK checks state == INITIAL before calling super.connect()
        if (state != State.IDLE) {
            Log.w(TAG, "already connecting/connected; state=$state")
            return@withContext state == State.REGISTERED
        }

        manualCloseRequested = false
        mConnectSucceeded = false
        // Reset PIN channel for this new connection attempt
        pinChannel = CompletableDeferred()

        val candidateUrls = linkedSetOf(
            "wss://${device.ip}:3001",
            "ws://${device.ip}:${if (device.port > 0) device.port else 3000}"
        )

        for (url in candidateUrls) {
            val isInsecureWs = url.startsWith("ws://", ignoreCase = true)
            if (isInsecureWs && !TransportSecurityPolicy.allowInsecureDeviceProtocol(
                    "LG WebOS SSAP over ws://", host = device.ip)) {
                continue
            }

            Log.i(TAG, "Connecting LG SSAP: $url")
            val connected = connectSingle(url)
            if (connected) {
                return@withContext true
            }
        }

        state = State.IDLE
        onPairingState(PairingState.IDLE)
        false
    }

    private suspend fun connectSingle(url: String): Boolean {
        state = State.CONNECTING

        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred
        val request = Request.Builder().url(url).build()
        val wsClient = if (url.startsWith("wss://", ignoreCase = true)) ssapTlsClient else client

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {

            // APK: onOpen → mConnectSucceeded = true → handleConnected() → helloTV()
            override fun onOpen(ws: WebSocket, response: Response) {
                mConnectSucceeded = true
                Log.d(TAG, "WS open ($url) → sending hello")
                ws.send(buildHello())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                handleMessage(ws, json)
            }

            // APK: onError → if mConnectSucceeded → handleConnectionLost, else handleConnectError
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure ($url): ${t.message}")
                val wasRegistered = state == State.REGISTERED
                state = State.IDLE
                if (mConnectSucceeded && wasRegistered) {
                    // Connection was live; treat as unexpected drop
                    if (!manualCloseRequested) {
                        onUnexpectedDisconnect("LG WebOS failure: ${t.message ?: "unknown"}")
                    }
                }
                mConnectSucceeded = false
                manualCloseRequested = false
                clearPendingRequests()
                synchronized(commandQueueLock) { commandQueue.clear() }
                if (!deferred.isCompleted) deferred.complete(false)
            }

            // APK: onClose → handleConnectionLost(z=true, exc=null)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed ($url) code=$code reason=$reason")
                val wasRegistered = state == State.REGISTERED
                state = State.IDLE
                if (wasRegistered && !manualCloseRequested) {
                    onUnexpectedDisconnect("LG WebOS closed: $reason")
                }
                mConnectSucceeded = false
                manualCloseRequested = false
                clearPendingRequests()
                synchronized(commandQueueLock) { commandQueue.clear() }
            }
        })

        try {
            // 5 minutes timeout: includes hello + register round-trips + PIN entry time by the user
            // (LG 8-digit PIN typically takes 5-15s to read from TV screen and type in, but some users need more time)
            return kotlinx.coroutines.withTimeout(300_000L) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "connect timeout ($url): ${e.message}")
            state = State.IDLE
            onPairingState(PairingState.IDLE) // Close dialog on timeout
            webSocket?.cancel()
            return false
        }
    }

    /**
     * Central message dispatcher — mirrors APK handleMessage(JSONObject).
     *
     * APK flow:
     *   type="hello"      → server says it's ready → sendRegister()
     *   type="response"   → intermediate ack (pairingType, etc.)
     *   type="registered" → extract client-key → handleRegistered()
     *   type="error"      → fail
     */
    private fun handleMessage(ws: WebSocket, json: JSONObject) {
        val type    = json.optString("type")
        val id      = json.optString("id")
        val payload = json.optJSONObject("payload")

        Log.d(TAG, "webOS [IN] type=$type id=$id")

        when (type) {
            // APK: handleConnected() calls helloTV() on open; TV confirms with hello → register
            "hello" -> {
                Log.d(TAG, "hello ACK → sending register")
                state = State.REGISTERING
                ws.send(buildRegistration())
            }

            // APK: sendRegister() handler — TV responds with pairingType before "registered"
            // This is the ack of the register request — TV tells us what pairing method it requires
            "response" -> {
                val reqId = id
                // Check if this is the register-ack (TV sending pairingType)
                val responsePairingType = payload?.optString("pairingType")
                if (!responsePairingType.isNullOrBlank()) {
                    when (responsePairingType.uppercase()) {
                        "PIN" -> {
                            // TV is displaying a PIN on screen — signal UI to show input dialog
                            // APK: onBeforeRegister(PairingType.PIN_CODE) → listener.onPairingRequired()
                            Log.i(TAG, "LG PIN pairing required")
                            onPairingState(PairingState.WAITING_FOR_PIN)
                            // After PIN is entered by user (via submitPin()), we send ssap://pairing/setPin
                            // The connect() coroutine waits for "registered" with a 60s timeout
                        }
                        "PROMPT" -> {
                            // TV is showing an Allow/Deny prompt (less common, older firmware)
                            Log.i(TAG, "LG PROMPT pairing — waiting for user to press Allow on TV")
                            onPairingState(PairingState.CONNECTING)
                        }
                        else -> Log.w(TAG, "LG unknown pairingType=$responsePairingType")
                    }
                    return
                }
                // It's a regular SSAP response — resolve pending coroutine
                pendingRequests[reqId]?.let { def ->
                    val returnValue = payload?.optBoolean("returnValue", true) ?: true
                    if (returnValue) {
                        def.complete(json)
                    } else {
                        val requestUri = pendingRequestUris[reqId]
                        def.completeExceptionally(
                            IOException("SSAP returnValue=false for id=$reqId uri=${requestUri ?: "unknown"}")
                        )
                    }
                }
            }

            // APK: handleRegistered() — client-key extracted, commandQueue drained
            "registered" -> {
                val clientKey = payload?.optString("client-key")
                if (!clientKey.isNullOrEmpty() && clientKey != liveToken) {
                    liveToken = clientKey
                    onTokenReceived(clientKey)
                    Log.i(TAG, "LG client-key received")
                }
                state = State.REGISTERED
                Log.i(TAG, "LG registered → draining ${commandQueue.size} queued commands")

                // PIN pairing complete — reset UI state
                onPairingState(PairingState.IDLE)

                // Drain command queue (APK: handleRegistered iterates commandQueue)
                synchronized(commandQueueLock) {
                    commandQueue.forEach { msg -> ws.send(msg) }
                    commandQueue.clear()
                }

                connectDeferred?.let {
                    if (!it.isCompleted) it.complete(true)
                }
            }

            "error" -> {
                val errorMsg = json.optString("error", "unknown error")
                val reqId = id
                val requestUri = pendingRequestUris[reqId]
                Log.w(TAG, "LG SSAP error: $errorMsg id=$reqId uri=${requestUri ?: "unknown"}")
                pendingRequests[reqId]?.completeExceptionally(
                    IOException("SSAP error: $errorMsg (id=$reqId uri=${requestUri ?: "unknown"})")
                )

                if (errorMsg.contains("insufficient permissions", ignoreCase = true)) {
                    Log.w(TAG, "LG insufficient permissions detected; clearing saved client-key for forced re-pair")
                    liveToken = null
                    onTokenReceived("")
                }

                // If error on register phase, fail the connect
                if (state == State.REGISTERING || state == State.CONNECTING) {
                    state = State.IDLE
                    onPairingState(PairingState.IDLE) // Close dialog on auth failure
                    connectDeferred?.let { if (!it.isCompleted) it.complete(false) }
                }
            }
        }
    }

    // ── TvController: disconnect ──────────────────────────────────────────────

    override fun disconnect() {
        manualCloseRequested = true
        state = State.IDLE
        pointerSocket?.close(1000, null)
        pointerSocket = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        clearPendingRequests()
        synchronized(commandQueueLock) { commandQueue.clear() }
    }

    override fun isConnected() = state == State.REGISTERED

    override fun getToken(): String? = liveToken
    override fun saveToken(token: String) { liveToken = token }

    /**
     * Submit the PIN displayed on the TV screen.
     * Called by DeviceConnectionManager.submitLgPin() after user enters the PIN in the dialog.
     *
     * APK evidence (WebOSTVServiceSocketClient.java line 472-503):
     *   sendPairingKey(str) → sends:
     *     { type:"request", id:N, uri:"ssap://pairing/setPin", payload:{pin:str} }
     *
     * TV responds with type="registered" + client-key if PIN is correct.
     */
    suspend fun submitPin(pin: String) {
        withContext(Dispatchers.IO) {
            val id = "pin_${msgId.incrementAndGet()}"
            val message = JSONObject().apply {
                put("type", "request")
                put("id", id)
                put("uri", "ssap://pairing/setPin")
                put("payload", JSONObject().apply {
                    put("pin", pin)
                })
            }.toString()
            Log.i(TAG, "Submitting LG PIN")
            webSocket?.send(message)
        }
    }

    private fun clearPendingRequests() {
        pendingRequests.values.forEach {
            it.completeExceptionally(IOException("WebSocket disconnected"))
        }
        pendingRequests.clear()
        pendingRequestUris.clear()
    }

    // ── SSAP request / response ───────────────────────────────────────────────

    /**
     * Sends an SSAP request and awaits the response.
     * If state < REGISTERED, the message is queued (APK: commandQueue pattern).
     * Note: queued messages do not return a response — fire-and-forget for key presses.
     */
    private suspend fun request(uri: String, payload: JSONObject? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            val id = "req_${msgId.incrementAndGet()}"
            val message = JSONObject().apply {
                put("type", "request")
                put("id", id)
                put("uri", uri)
                payload?.let { put("payload", it) }
            }.toString()

            if (state != State.REGISTERED) {
                if (state == State.CONNECTING || state == State.REGISTERING) {
                    // Queue only while handshake is in progress.
                    synchronized(commandQueueLock) { commandQueue.addLast(message) }
                    return@withContext null
                }
                throw IOException("LG WebOS socket disconnected")
            }

            val deferred = CompletableDeferred<JSONObject>()
            pendingRequests[id] = deferred
            pendingRequestUris[id] = uri
            webSocket?.send(message)
            try {
                kotlinx.coroutines.withTimeout(5_000L) { deferred.await() }
            } finally {
                pendingRequests.remove(id)
                pendingRequestUris.remove(id)
            }
        }

    // ── TvController: sendKey ─────────────────────────────────────────────────

    override suspend fun sendKey(key: TvKey) {
        when {
            // 1. Pointer-socket keys (APK: sendSpecialKey / ok via mouseSocket)
            key in POINTER_SOCKET_KEYS -> {
                val buttonName = POINTER_BUTTON_MAP[key] ?: return
                sendPointerButton(buttonName)
            }

            // 2. Dedicated SSAP endpoints
            SSAP_KEY_MAP.containsKey(key) -> {
                when (key) {
                    TvKey.MUTE -> request("ssap://audio/setMute", JSONObject().put("mute", true))
                    else       -> request(SSAP_KEY_MAP[key]!!)
                }
            }

            // 3. Special-key path via pointer socket (avoid unsupported IME sendKeyEvent on some models)
            IME_KEY_MAP.containsKey(key) -> {
                sendPointerButton(IME_KEY_MAP[key]!!)
            }

            else -> throw UnsupportedOperationException("LG WebOS does not support key: $key")
        }
    }

    // ── TvController: sendText ────────────────────────────────────────────────

    override suspend fun sendText(text: String) {
        request(
            uri     = "ssap://com.webos.service.ime/insertText",
            payload = JSONObject().apply {
                put("text", text)
                put("replace", 0)
            }
        )
    }

    // ── TvController: getInstalledApps ────────────────────────────────────────

    override suspend fun getInstalledApps(): List<TvApp> {
        val resp = request("ssap://com.webos.applicationManager/listApps") ?: return emptyList()
        val apps = resp.optJSONObject("payload")?.optJSONArray("apps") ?: return emptyList()
        val baseUrl = "http://${device.ip}:${device.port}"
        return (0 until apps.length()).mapNotNull { i ->
            runCatching {
                val app = apps.getJSONObject(i)
                val iconRaw = app.optString("icon").takeIf { it.isNotEmpty() }
                TvApp(
                    id      = app.optString("id"),
                    name    = app.optString("title"),
                    iconUrl = normalizeIconUrl(baseUrl, iconRaw)
                )
            }.getOrNull()
        }
    }

    private fun normalizeIconUrl(baseUrl: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return if (value.startsWith("/")) "$baseUrl$value" else "$baseUrl/$value"
    }

    // ── TvController: launchApp ───────────────────────────────────────────────

    override suspend fun launchApp(appId: String) {
        request(
            uri     = "ssap://system.launcher/launch",
            payload = JSONObject().apply { put("id", appId) }
        )
    }

    // ── Mouse pointer (separate WebSocket — APK: WebOSTVMouseSocketConnection) ─

    /**
     * Sends a button event via the pointer socket (APK: mouseSocket.button(str)).
     * Opens the pointer socket on demand if not yet available, using a mutex to
     * prevent duplicate connections at 60fps touch events.
     */
    private suspend fun sendPointerButton(buttonName: String) {
        withContext(Dispatchers.IO) {
            ensurePointerSocket()
            // APK: "type:button\nname:HOME\n\n"
            val sent = pointerSocket?.send("type:button\nname:$buttonName\n\n") == true
            if (!sent) {
                pointerSocket = null
                throw IOException("Pointer socket send failed for button=$buttonName")
            }
        }
    }

    private suspend fun ensurePointerSocket() = pointerMutex.withLock {
        if (pointerSocket != null) return@withLock
        val resp = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val socketPath = resp?.optJSONObject("payload")?.optString("socketPath")
            ?: throw IOException("LG pointer socketPath missing")

        pointerSocketDeferred = CompletableDeferred()
        val req = Request.Builder().url(socketPath).build()
        val wsClient = if (socketPath.startsWith("wss://", ignoreCase = true)) {
            Log.i(TAG, "Opening LG pointer socket via permissive TLS client: $socketPath")
            pointerTlsClient
        } else {
            client
        }

        pointerSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "pointer socket open: $socketPath")
                pointerSocketDeferred?.complete(true)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "pointer socket closed code=$code reason=$reason")
                pointerSocket = null
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "pointer socket failure: ${t.message}")
                pointerSocket = null
                pointerSocketDeferred?.completeExceptionally(t)
            }
        })
        val ok = kotlinx.coroutines.withTimeoutOrNull(3_000L) { pointerSocketDeferred?.await() }
        pointerSocketDeferred = null
        if (ok != true) throw UnsupportedOperationException(
            "Chưa thể kích hoạt chuột trên TV này. Hãy thử lại."
        )
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        withContext(Dispatchers.IO) {
            ensurePointerSocket()
            // APK: "type:move\ndx:X\ndy:Y\ndown:0\n\n"
            val sent = pointerSocket?.send("type:move\ndx:${dx.toInt()}\ndy:${dy.toInt()}\ndown:0\n\n") == true
            if (!sent) {
                pointerSocket = null
                throw IOException("Pointer move send failed")
            }
        }
    }

    override suspend fun tapMouse() {
        withContext(Dispatchers.IO) {
            ensurePointerSocket()
            // APK: "type:click\n\n"
            val sent = pointerSocket?.send("type:click\n\n") == true
            if (!sent) {
                pointerSocket = null
                throw IOException("Pointer click send failed")
            }
        }
    }
}

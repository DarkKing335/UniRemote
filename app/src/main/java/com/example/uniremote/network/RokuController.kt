package com.example.uniremote.network

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParserFactory
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "RokuController"

/**
 * Roku TV controller using the External Control REST API (ECP).
 * API Documentation: https://developer.roku.com/docs/developer-program/debugging/external-control-api.md
 *
 * Commands are sent via HTTP POST to: http://<ip>:8060/keypress/<Command>
 */
class RokuController(
    override val device: TvDevice,
    private val appContext: Context? = null
) : TvController {

    private enum class TransportMode {
        HTTP,
        WEBSOCKET
    }

    private enum class CommandKind {
        KEY_PRESS,
        KEY_DOWN,
        KEY_UP,
        LAUNCH,
        QUERY_APPS
    }

    private data class RokuCommand(
        val kind: CommandKind,
        val value: String? = null
    )

    private data class CommandResult(
        val success: Boolean,
        val responseCode: Int? = null,
        val body: String? = null,
        val error: Throwable? = null
    )

    // Roku ECP uses fire-and-forget HTTP POSTs — retries on failure would double-press buttons.
    // Use a dedicated client with retryOnConnectionFailure=false to prevent duplicate key events.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val wsHandler = RokuWsSessionHandler(client, appContext?.applicationContext)
    private val transportMutex = Mutex()
    private val queueLock = Object()
    private val commandQueue = ArrayDeque<QueuedCommand>()

    @Volatile
    private var workerThread: Thread? = null

    @Volatile private var activeIp: String = device.ip
    private val baseUrl: String
        get() = "http://$activeIp:${device.port}"

    private fun isRokuCompatibilityAllowed(): Boolean {
        return TransportSecurityPolicy.allowCleartextForUrl(baseUrl, "Roku ECP")
    }

    // @Volatile: read/written from IO coroutines and OkHttp callback threads
    @Volatile private var isConnected = false
    @Volatile private var transportMode: TransportMode = TransportMode.HTTP
    @Volatile private var failureReason: String? = null

    private data class QueuedCommand(
        val command: RokuCommand,
        val result: CompletableDeferred<CommandResult>
    )

    val isFallbackMode: Boolean
        get() = transportMode == TransportMode.WEBSOCKET

    companion object {
        const val ROKU_NETWORK_ACCESS_LIMITED_MESSAGE =
            "Roku device is not accepting remote connections. On TV go to Settings > System > Advanced system settings > Control by mobile apps > Network access and set it to Enabled."

        internal const val ROKU_REMOTE_BLOCKED_MESSAGE = ROKU_NETWORK_ACCESS_LIMITED_MESSAGE

        private const val WS_RETRY_ATTEMPTS = 3
        private const val REDISCOVERY_RETRY_ATTEMPTS = 2
        private val VOLUME_KEYS = setOf("VolumeUp", "VolumeDown", "VolumeMute")

        internal fun mapEcpAccessFailure(statusCode: Int?): String? = when (statusCode) {
            401, 403 -> ROKU_REMOTE_BLOCKED_MESSAGE
            else -> null
        }

        private val KEY_MAP = mapOf(
            TvKey.UP       to "Up",
            TvKey.DOWN     to "Down",
            TvKey.LEFT     to "Left",
            TvKey.RIGHT    to "Right",
            TvKey.OK       to "Select",
            TvKey.BACK     to "Back",
            TvKey.HOME     to "Home",
            TvKey.MENU     to "Star",     // Roku uses Star (*) for the options/context menu
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
            TvKey.SOURCE   to "InputTuner",
            TvKey.HDMI_1   to "InputHDMI1",
            TvKey.HDMI_2   to "InputHDMI2",
            TvKey.HDMI_3   to "InputHDMI3",
            TvKey.HDMI_4   to "InputHDMI4",
            TvKey.AV       to "InputAV1",
        )
    }

    private fun sendRokuNetworkErrorBroadcast() {
        val context = appContext ?: return
        val intent = Intent("ROKU_NETWORK_ERROR")
        intent.setPackage(context.packageName)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        ensureWorkerThread()
        transportMutex.withLock {
            connectLocked()
        }
    }

    override fun disconnect() {
        isConnected = false
        wsHandler.close("disconnect")
    }

    override fun isConnected(): Boolean = isConnected

    override fun getConnectionFailureReason(): String? = failureReason

    override fun clearConnectionFailureReason() {
        failureReason = null
    }

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val rokuKey = KEY_MAP[key]
            ?: throw UnsupportedOperationException("Roku does not support key: $key")
        sendCommandOrThrow(RokuCommand(CommandKind.KEY_PRESS, rokuKey))
    }

    suspend fun sendKeyDown(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val rokuKey = KEY_MAP[key]
            ?: throw UnsupportedOperationException("Roku does not support key: $key")
        sendCommandOrThrow(RokuCommand(CommandKind.KEY_DOWN, rokuKey))
    }

    suspend fun sendKeyUp(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val rokuKey = KEY_MAP[key]
            ?: throw UnsupportedOperationException("Roku does not support key: $key")
        sendCommandOrThrow(RokuCommand(CommandKind.KEY_UP, rokuKey))
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
            sendCommandOrThrow(RokuCommand(CommandKind.KEY_PRESS, endpoint))
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<TvApp>()
        runCatching {
            val result = enqueueAndAwait(RokuCommand(CommandKind.QUERY_APPS))
            if (!result.success || result.body.isNullOrBlank()) {
                Log.w(TAG, "Failed to query Roku apps. code=${result.responseCode}, err=${result.error?.message}")
                return@runCatching
            }
            val xml = result.body

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
                            // Icon URL per APK reverse-engineering (Y6/h.java):
                            // http://IP:8060/query/icon/{id}
                            val iconUrl = "http://$activeIp:${device.port}/query/icon/$appId"
                            apps.add(TvApp(id = appId, name = appName, iconUrl = iconUrl))
                            appId = ""
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        apps
    }

    override suspend fun launchApp(appId: String): Unit = withContext(Dispatchers.IO) {
        sendCommandOrThrow(RokuCommand(CommandKind.LAUNCH, appId))
    }

    private suspend fun sendCommandOrThrow(command: RokuCommand) {
        val result = enqueueAndAwait(command)

        if (result.success) return

        val message = failureReason ?: result.error?.message ?: ROKU_REMOTE_BLOCKED_MESSAGE
        throw IllegalStateException(message, result.error)
    }

    private suspend fun connectLocked(): Boolean {
        clearConnectionFailureReason()
        activeIp = device.ip
        wsHandler.close("reconnect")
        transportMode = TransportMode.HTTP

        if (!isRokuCompatibilityAllowed()) {
            failureReason = "Roku ECP over HTTP is blocked by current transport policy."
            isConnected = false
            return false
        }

        // Step 1: try HTTP ECP first.
        val httpProbe = runHttpPostWithRetry(
            path = "/keypress/home",
            attempts = 2
        )
        if (httpProbe.success) {
            isConnected = true
            transportMode = TransportMode.HTTP
            clearConnectionFailureReason()
            Log.i(TAG, "Roku connected over HTTP ECP at $activeIp")
            return true
        }

        // Trigger WS fallback when:
        //  a) Roku explicitly rejects the request (401/403) — "Network Access = Disabled" on TV
        //  b) responseCode is null AND error is not ConnectException — this happens in release
        //     builds where Android NSC (cleartextTrafficPermitted=false) blocks the HTTP request
        //     at the OS socket layer before it even reaches Roku, throwing IOException instead
        //     of returning a 401/403. UniMote APK avoids this by having usesCleartextTraffic=true
        //     globally, which allows HTTP to go through and receive Roku's real status code.
        //     Without this fix, the WS fallback is NEVER triggered in release builds.
        val shouldFallbackToWs = httpProbe.responseCode == 401
            || httpProbe.responseCode == 403
            || isHttpOsBlocked(httpProbe)

        if (!shouldFallbackToWs) {
            isConnected = false
            failureReason = ROKU_REMOTE_BLOCKED_MESSAGE
            sendRokuNetworkErrorBroadcast()
            Log.w(TAG, "Roku HTTP probe failed without WS fallback code. code=${httpProbe.responseCode} err=${httpProbe.error?.javaClass?.simpleName}")
            return false
        }

        val wsReason = if (httpProbe.responseCode != null) "HTTP ${httpProbe.responseCode}" else "OS cleartext block"
        Log.w(TAG, "HTTP probe failed ($wsReason), attempting WebSocket fallback for $activeIp")

        // Step 2-5: switch to WS fallback on blocked/failed HTTP.
        val wsReady = ensureWebSocketConnectedWithRecovery()
        if (wsReady) {
            isConnected = true
            transportMode = TransportMode.WEBSOCKET
            clearConnectionFailureReason()
            Log.i(TAG, "Roku connected in WebSocket fallback mode at $activeIp")
            return true
        }

        isConnected = false
        failureReason = ROKU_REMOTE_BLOCKED_MESSAGE
        sendRokuNetworkErrorBroadcast()
        Log.e(TAG, "Both HTTP and WebSocket transports failed for Roku at $activeIp")
        return false
    }

    /**
     * Returns true when the HTTP call failed NOT because the device is unreachable (ConnectException)
     * but because Android's Network Security Config blocked the cleartext request at the OS level
     * (throws IOException with no response code).
     *
     * Release builds have cleartextTrafficPermitted=false in network_security_config.xml.
     * In that case OkHttp never sends the request to Roku — it throws IOException before the
     * TCP connection is made, so responseCode is null. We still want to try the WebSocket path
     * because ws:// cleartext is also allowed by the same NSC config (and because Roku's WS
     * endpoint bypasses the HTTP-level restrictions anyway).
     */
    private fun isHttpOsBlocked(result: CommandResult): Boolean {
        if (result.responseCode != null) return false          // got a real HTTP response
        val err = result.error ?: return false                 // no error at all
        if (err is java.net.ConnectException) return false    // device unreachable, WS won't help
        // IOException without a response code = OS-level block (cleartext NSC, socket policy, etc.)
        return err is java.io.IOException
    }

    private suspend fun executeCommandLocked(command: RokuCommand): CommandResult {
        if (!isConnected && !connectLocked()) {
            return CommandResult(success = false, error = IllegalStateException(failureReason))
        }

        return when (transportMode) {
            TransportMode.HTTP -> executeViaHttpThenFallbackLocked(command)
            TransportMode.WEBSOCKET -> executeViaWebSocketThenHttpLocked(command)
        }
    }

    private suspend fun executeViaHttpThenFallbackLocked(command: RokuCommand): CommandResult {
        val httpResult = executeHttpCommand(command)
        if (httpResult.success) {
            isConnected = true
            clearConnectionFailureReason()
            return httpResult
        }

        // Same OS-block detection as connectLocked(): fall through to WS when HTTP is
        // blocked at the Android NSC layer (IOException, null responseCode, not ConnectException).
        val httpExplicitlyBlocked = httpResult.responseCode == 401 || httpResult.responseCode == 403
        val httpOsBlocked = isHttpOsBlocked(httpResult)

        if (!httpExplicitlyBlocked && !httpOsBlocked) {
            isConnected = false
            return CommandResult(
                success = false,
                responseCode = httpResult.responseCode,
                error = httpResult.error
            )
        }

        val reason = if (httpExplicitlyBlocked) "HTTP ${httpResult.responseCode}" else "OS cleartext block"
        Log.w(TAG, "HTTP command blocked ($reason). Switching to WebSocket fallback")

        val wsResult = executeWebSocketCommandWithRetry(command)
        if (wsResult.success) {
            transportMode = TransportMode.WEBSOCKET
            isConnected = true
            clearConnectionFailureReason()
            return wsResult
        }

        isConnected = false
        failureReason = ROKU_REMOTE_BLOCKED_MESSAGE
        sendRokuNetworkErrorBroadcast()
        Log.e(
            TAG,
            "HTTP and WS command paths both failed. httpCode=${httpResult.responseCode}, httpErr=${httpResult.error?.message}, wsErr=${wsResult.error?.message}"
        )
        return CommandResult(
            success = false,
            responseCode = httpResult.responseCode,
            error = wsResult.error ?: httpResult.error ?: IllegalStateException(ROKU_REMOTE_BLOCKED_MESSAGE)
        )
    }

    private suspend fun executeViaWebSocketThenHttpLocked(command: RokuCommand): CommandResult {
        val wsResult = executeWebSocketCommandWithRetry(command)
        if (wsResult.success) {
            isConnected = true
            clearConnectionFailureReason()
            return wsResult
        }

        // If WS fallback is temporarily down, try HTTP one more time before surfacing failure.
        val httpResult = executeHttpCommand(command)
        if (httpResult.success) {
            transportMode = TransportMode.HTTP
            isConnected = true
            clearConnectionFailureReason()
            return httpResult
        }

        isConnected = false
        failureReason = ROKU_REMOTE_BLOCKED_MESSAGE
        sendRokuNetworkErrorBroadcast()
        Log.e(TAG, "WS command failed and HTTP recovery failed. wsErr=${wsResult.error?.message}, httpCode=${httpResult.responseCode}")
        return CommandResult(
            success = false,
            responseCode = httpResult.responseCode,
            error = wsResult.error ?: httpResult.error ?: IllegalStateException(ROKU_REMOTE_BLOCKED_MESSAGE)
        )
    }

    private suspend fun executeWebSocketCommandWithRetry(command: RokuCommand): CommandResult {
        repeat(WS_RETRY_ATTEMPTS) { attempt ->
            val ready = ensureWebSocketConnectedWithRecovery()
            if (!ready) {
                val backoffMs = min(1_500L, (attempt + 1) * 500L)
                delay(backoffMs)
                return@repeat
            }

            val wsResult = wsHandler.send(
                ip = activeIp,
                command = mapToWsCommand(command)
            )
            val result = CommandResult(
                success = wsResult.success,
                body = wsResult.body,
                error = wsResult.error
            )
            if (result.success) {
                return result
            }

            Log.w(TAG, "WS command attempt=${attempt + 1} failed: ${result.error?.message}")
            wsHandler.close("retry")
            refreshIpFromRediscovery()
            val backoffMs = min(1_500L, (attempt + 1) * 500L)
            delay(backoffMs)
        }

        return CommandResult(
            success = false,
            error = IllegalStateException("WebSocket fallback retries exhausted")
        )
    }

    private suspend fun ensureWebSocketConnectedWithRecovery(): Boolean {
        repeat(WS_RETRY_ATTEMPTS) { attempt ->
            if (wsHandler.connectAndAuthenticate(activeIp)) {
                return true
            }

            Log.w(TAG, "WS connect/auth attempt=${attempt + 1} failed for ip=$activeIp")
            wsHandler.close("connect-failed")
            refreshIpFromRediscovery()
            val backoffMs = min(1_500L, (attempt + 1) * 500L)
            delay(backoffMs)
        }
        return false
    }

    private suspend fun refreshIpFromRediscovery() {
        val context = appContext ?: return
        runCatching {
            var bestIp: String? = null
            repeat(REDISCOVERY_RETRY_ATTEMPTS) {
                val devices = RokuSsdpDiscovery(context, timeoutMs = 1_600, rounds = 1)
                    .discover()
                    .firstOrNull()
                    .orEmpty()
                val candidate = devices.firstOrNull { it.id == device.id }
                    ?: devices.firstOrNull { it.name.equals(device.name, ignoreCase = true) }
                    ?: devices.firstOrNull()
                if (candidate != null) {
                    bestIp = candidate.ip
                    return@repeat
                }
            }

            val ip = bestIp ?: return@runCatching
            if (ip != activeIp) {
                Log.i(TAG, "Roku rediscovery updated IP from $activeIp to $ip")
                activeIp = ip
            } else {
                Log.d(TAG, "Roku rediscovery kept same IP $activeIp")
            }
        }.onFailure {
            Log.w(TAG, "Roku rediscovery failed during WS recovery", it)
        }
    }

    private fun mapToWsCommand(command: RokuCommand): RokuWsSessionHandler.WsCommand {
        return when (command.kind) {
            CommandKind.KEY_PRESS -> RokuWsSessionHandler.WsCommand(
                request = "key-press",
                value = command.value,
                responseBodyExpected = false
            )
            CommandKind.KEY_DOWN -> RokuWsSessionHandler.WsCommand(
                request = "key-down",
                value = command.value,
                responseBodyExpected = false
            )
            CommandKind.KEY_UP -> RokuWsSessionHandler.WsCommand(
                request = "key-up",
                value = command.value,
                responseBodyExpected = false
            )
            CommandKind.LAUNCH -> RokuWsSessionHandler.WsCommand(
                request = "launch",
                value = command.value,
                responseBodyExpected = false
            )
            CommandKind.QUERY_APPS -> RokuWsSessionHandler.WsCommand(
                request = "query-apps",
                value = null,
                responseBodyExpected = true
            )
        }
    }

    private suspend fun executeHttpCommand(command: RokuCommand): CommandResult {
        return when (command.kind) {
            CommandKind.KEY_PRESS -> runHttpPostWithRetry("/keypress/${command.value}", attempts = 1)
            CommandKind.KEY_DOWN -> runHttpPostWithRetry("/keydown/${command.value}", attempts = 1)
            CommandKind.KEY_UP -> runHttpPostWithRetry("/keyup/${command.value}", attempts = 1)
            CommandKind.LAUNCH -> runHttpPostWithRetry("/launch/${command.value}", attempts = 1)
            CommandKind.QUERY_APPS -> runGetWithRetry("$baseUrl/query/apps", attempts = 2)
        }
    }

    private suspend fun enqueueAndAwait(command: RokuCommand): CommandResult {
        ensureWorkerThread()

        val deferred = CompletableDeferred<CommandResult>()
        synchronized(queueLock) {
            if (isVolumeCommand(command)) {
                val iterator = commandQueue.iterator()
                while (iterator.hasNext()) {
                    val pending = iterator.next()
                    if (isVolumeCommand(pending.command)) {
                        iterator.remove()
                        pending.result.complete(CommandResult(success = true))
                    }
                }
            }

            commandQueue.addLast(QueuedCommand(command, deferred))
            queueLock.notifyAll()
        }

        return deferred.await()
    }

    private fun ensureWorkerThread() {
        if (workerThread?.isAlive == true) {
            return
        }

        synchronized(queueLock) {
            if (workerThread?.isAlive == true) {
                return
            }

            workerThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    val queued = synchronized(queueLock) {
                        while (commandQueue.isEmpty()) {
                            try {
                                queueLock.wait()
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                        commandQueue.removeFirst()
                    }

                    val result = runCatching {
                        runBlocking {
                            transportMutex.withLock {
                                executeCommandLocked(queued.command)
                            }
                        }
                    }.getOrElse {
                        CommandResult(success = false, error = it)
                    }

                    queued.result.complete(result)
                }
            }.apply {
                name = "RokuCommandWorker"
                isDaemon = true
                start()
            }
        }
    }

    private fun isVolumeCommand(command: RokuCommand): Boolean {
        return (command.kind == CommandKind.KEY_PRESS ||
            command.kind == CommandKind.KEY_DOWN ||
            command.kind == CommandKind.KEY_UP) &&
            command.value in VOLUME_KEYS
    }

    private suspend fun runHttpPostWithRetry(path: String, attempts: Int): CommandResult {
        var lastCode: Int? = null
        var lastError: Throwable? = null

        repeat(attempts) { index ->
            val result = runCatching {
                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .post("".toRequestBody())
                    .build()
                client.newCall(request).execute().use { response ->
                    CommandResult(
                        success = response.isSuccessful,
                        responseCode = response.code,
                        body = response.body?.string()
                    )
                }
            }.getOrElse {
                lastError = it
                if (it is ConnectException) {
                    sendRokuNetworkErrorBroadcast()
                }
                CommandResult(success = false, error = it)
            }

            if (result.success) {
                return result
            }

            lastCode = result.responseCode
            mapEcpAccessFailure(result.responseCode)?.let { failureReason = it }
            if (index < attempts - 1) {
                delay(min(500L, (index + 1) * 250L))
            }
        }

        return CommandResult(success = false, responseCode = lastCode, error = lastError)
    }

    private suspend fun runGetWithRetry(url: String, attempts: Int): CommandResult {
        var lastCode: Int? = null
        var lastError: Throwable? = null

        repeat(attempts) { index ->
            val result = runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { response ->
                    val body = response.body?.string()
                    CommandResult(
                        success = response.isSuccessful,
                        responseCode = response.code,
                        body = body
                    )
                }
            }.getOrElse {
                lastError = it
                if (it is ConnectException) {
                    sendRokuNetworkErrorBroadcast()
                }
                CommandResult(success = false, error = it)
            }

            if (result.success) {
                clearConnectionFailureReason()
                return result
            }

            lastCode = result.responseCode
            mapEcpAccessFailure(result.responseCode)?.let { reason ->
                failureReason = reason
            }
            if (index < attempts - 1) {
                val backoffMs = min(800L, (index + 1) * 300L)
                kotlinx.coroutines.delay(backoffMs)
            }
        }

        return CommandResult(
            success = false,
            responseCode = lastCode,
            error = lastError
        )
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("Roku TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }

    override suspend fun tapMouse() {
        throw UnsupportedOperationException("Roku TV không hỗ trợ điều khiển chuột. Dùng tab D-Pad để điều hướng.")
    }
}

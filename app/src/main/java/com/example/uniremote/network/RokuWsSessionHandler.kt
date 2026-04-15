package com.example.uniremote.network

import android.content.Intent
import android.content.Context
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private const val WS_TAG = "RokuWsHandler"
private const val AUTH_SALT = "F3A278B8-1C6F-44A9-9D89-F1979CA4C6F1"

internal class RokuWsSessionHandler(
    private val client: OkHttpClient,
    private val appContext: Context?
) {

    data class WsSendResult(
        val success: Boolean,
        val body: String? = null,
        val error: Throwable? = null
    )

    data class WsCommand(
        val request: String,
        val value: String?,
        val responseBodyExpected: Boolean
    )

    private val requestId = AtomicInteger(1)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var authenticated = false

    @Volatile
    private var activeIp: String? = null

    @Volatile private var pendingCommand: WsCommand? = null

    // Accessed from both IO coroutine and OkHttp callback thread — must be @Volatile.
    @Volatile private var authDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var queryAppsDeferred: CompletableDeferred<String?>? = null

    suspend fun connectAndAuthenticate(ip: String): Boolean = withContext(Dispatchers.IO) {
        if (authenticated && socket != null && activeIp == ip) {
            return@withContext true
        }

        close("reopen")
        activeIp = ip

        val authWaiter = CompletableDeferred<Boolean>()
        authDeferred = authWaiter

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(WS_TAG, "WS opened for Roku ip=$ip code=${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleTextMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(WS_TAG, "WS closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(WS_TAG, "WS closed code=$code reason=$reason")
                authenticated = false
                authDeferred?.complete(false)
                queryAppsDeferred?.complete(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(WS_TAG, "WS failure for Roku", t)
                authenticated = false
                authDeferred?.complete(false)
                queryAppsDeferred?.complete(null)
            }
        }

        val request = Request.Builder()
            .url("ws://$ip:8060/ecp-session")
            .addHeader("Sec-WebSocket-Protocol", "ecp-2")
            .build()

        socket = client.newWebSocket(request, listener)

        val connected = withTimeoutOrNull(6_000L) {
            authWaiter.await()
        } == true

        if (!connected) {
            Log.w(WS_TAG, "WS auth timeout/failure for Roku ip=$ip")
            close("auth-timeout")
        }

        connected
    }

    suspend fun send(ip: String, command: WsCommand): WsSendResult = withContext(Dispatchers.IO) {
        val requiresAuthRoundTrip = !(authenticated && socket != null && activeIp == ip)
        if (requiresAuthRoundTrip) {
            pendingCommand = command
        }

        val ready = connectAndAuthenticate(ip)
        if (!ready) {
            return@withContext WsSendResult(
                success = false,
                error = IllegalStateException("WebSocket auth failed")
            )
        }

        val effectiveCommand = consumePendingCommandAfterAuth() ?: command
        val payload = buildRequestPayload(effectiveCommand)
        val ws = socket
        if (ws == null) {
            return@withContext WsSendResult(
                success = false,
                error = IllegalStateException("WebSocket not connected")
            )
        }

        if (effectiveCommand.responseBodyExpected) {
            val waiter = CompletableDeferred<String?>()
            queryAppsDeferred = waiter
            val sent = ws.send(payload)
            if (!sent) {
                queryAppsDeferred = null
                return@withContext WsSendResult(
                    success = false,
                    error = IllegalStateException("WebSocket send failed")
                )
            }

            val body = withTimeoutOrNull(5_000L) {
                waiter.await()
            }
            queryAppsDeferred = null

            if (body.isNullOrBlank()) {
                return@withContext WsSendResult(
                    success = false,
                    error = IllegalStateException("WebSocket response timeout or empty payload")
                )
            }

            return@withContext WsSendResult(success = true, body = body)
        }

        val sent = ws.send(payload)
        if (!sent) {
            return@withContext WsSendResult(
                success = false,
                error = IllegalStateException("WebSocket send failed")
            )
        }

        WsSendResult(success = true)
    }

    fun close(reason: String) {
        Log.d(WS_TAG, "Closing Roku WS: $reason")
        // Complete pending deferreds BEFORE nulling them.
        // If close() is called externally while authWaiter.await() is running (e.g. on retry),
        // failing to complete here would leave authWaiter blocked until the 6s timeout.
        authDeferred?.complete(false)
        queryAppsDeferred?.complete(null)
        socket?.close(1000, reason)
        socket = null
        authenticated = false
        pendingCommand = null
        authDeferred = null
        queryAppsDeferred = null
    }

    private fun handleTextMessage(webSocket: WebSocket, text: String) {
        runCatching {
            val json = JSONObject(text)

            if (json.optString("notify") == "authenticate") {
                val challenge = json.optString("param-challenge")
                if (challenge.isNotBlank()) {
                    val authPayload = buildAuthenticatePayload(challenge)
                    webSocket.send(authPayload)
                }
                return
            }

            val request = json.optString("request")
            val status = json.optInt("status", -1)

            if (request == "authenticate") {
                authenticated = status == 200
                authDeferred?.complete(authenticated)
                if (!authenticated) {
                    Log.w(WS_TAG, "Roku WS auth rejected: status=$status")
                }
                return
            }

            if (request == "query-apps") {
                if (status == 200) {
                    val encoded = json.optString("content-data")
                    val decoded = decodeBase64OrNull(encoded)
                    sendQueryAppsCompletedBroadcast(decoded)
                    queryAppsDeferred?.complete(decoded)
                } else {
                    queryAppsDeferred?.complete(null)
                }
            }
        }.onFailure {
            Log.w(WS_TAG, "Failed to parse Roku WS message: $text", it)
        }
    }

    private fun buildAuthenticatePayload(challenge: String): String {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest((challenge + AUTH_SALT).toByteArray(StandardCharsets.UTF_8))
        val response = Base64.getEncoder().encodeToString(hash)
        val json = JSONObject()
        json.put("param-response", response)
        json.put("request", "authenticate")
        // APK: Integer.valueOf(getAndIncrement()) — request-id is an integer, not a string.
        json.put("request-id", requestId.getAndIncrement())
        return json.toString()
    }

    private fun buildRequestPayload(command: WsCommand): String {
        val normalizedRequest = normalizeRequest(command.request)
        val json = JSONObject()
        json.put("request", normalizedRequest)
        // APK: Integer.valueOf(getAndIncrement()) — request-id is an integer, not a string.
        json.put("request-id", requestId.getAndIncrement())

        when (normalizedRequest) {
            "launch" -> if (!command.value.isNullOrBlank()) {
                json.put("param-channel-id", command.value)
            }
            "query-apps" -> Unit
            else -> if (!command.value.isNullOrBlank()) {
                json.put("param-key", command.value)
            }
        }
        return json.toString()
    }

    private fun normalizeRequest(request: String): String {
        return when (request) {
            "keypress" -> "key-press"
            "keydown" -> "key-down"
            "keyup" -> "key-up"
            else -> request
        }
    }

    private fun consumePendingCommandAfterAuth(): WsCommand? {
        val command = pendingCommand ?: return null
        pendingCommand = null

        val normalizedRequest = normalizeRequest(command.request)
        if (normalizedRequest == "key-down" || normalizedRequest == "key-up") {
            return command.copy(request = "key-press")
        }

        return command
    }

    private fun decodeBase64OrNull(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val decoded = Base64.getDecoder().decode(encoded)
            String(decoded, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun sendQueryAppsCompletedBroadcast(decodedContent: String?) {
        val context = appContext ?: return
        val intent = Intent("QUERY_APPS_COMPLETED")
        intent.setPackage(context.packageName)
        intent.putExtra("QUERY_APPS", decodedContent)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}

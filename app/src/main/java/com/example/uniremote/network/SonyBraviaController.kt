package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sony Bravia IRCC-IP controller — for older Sony TVs that do NOT run Android TV
 * and therefore do NOT support the Google TV Remote Protocol (port 6466/6467).
 *
 * Typical candidates: KDL series (2012–2016) running pre-Android firmware.
 *
 * Protocol: HTTP/SOAP on port 80 (or 443 with self-signed cert)
 *   Endpoint: POST /sony/IRCC
 *   Auth: X-Auth-PSK header (4-digit PIN entered once by user)
 *
 * Pairing flow:
 *   1. App registers via POST /sony/accessControl/actRegister
 *   2. TV shows a 4-digit PIN on screen
 *   3. User enters PIN in the app
 *   4. App completes registration with Basic auth: base64("0000:<pin>")
 *   5. TV responds with Set-Cookie: auth=<token>
 *   6. App stores the cookie/PSK for all subsequent requests
 *
 * Reference:
 *   https://pro-bravia.sony.net/develop/integrate/ircc-ip/
 *   https://github.com/benladen/SonyBraviaRemote
 *
 * Note: Sony KDL-43W800F (the user's TV) shows Google TV pairing dialog →
 * it IS running Android TV → use GoogleTvController instead.
 * This controller is the fallback for models that show NO pairing dialog.
 */
class SonyBraviaController(
    override val device: TvDevice,
    private val pinChannel: Channel<String>,
    private val onPairingState: (PairingState) -> Unit,
    private val onTokenReceived: (String) -> Unit = {}
) : TvController {

    companion object {
        private const val TAG = "SonyBraviaCtrl"
        private const val ENDPOINT_IRCC   = "/sony/IRCC"
        private const val ENDPOINT_ACCESS = "/sony/accessControl/actRegister"
        private const val ENDPOINT_SYSTEM = "/sony/system"

        /**
         * IRCC IR codes for Sony Bravia.
         * Full list: https://pro-bravia.sony.net/develop/integrate/ircc-ip/ircc-codes/
         */
        private val KEY_MAP = mapOf(
            TvKey.POWER     to "AAAAAQAAAAEAAAAVAw==",
            TvKey.UP        to "AAAAAQAAAAEAAAB0Aw==",
            TvKey.DOWN      to "AAAAAQAAAAEAAAB1Aw==",
            TvKey.LEFT      to "AAAAAQAAAAEAAAB2Aw==",
            TvKey.RIGHT     to "AAAAAQAAAAEAAAAzAw==",
            TvKey.OK        to "AAAAAQAAAAEAAABlAw==",
            TvKey.BACK      to "AAAAAgAAAJcAAAAjAw==",
            TvKey.HOME      to "AAAAAQAAAAEAAABgAw==",
            TvKey.MENU      to "AAAAAgAAAJcAAAAYAw==",
            TvKey.EXIT      to "AAAAAgAAAJcAAAAXAw==",
            TvKey.INFO      to "AAAAAgAAAJcAAAAdAw==",
            TvKey.GUIDE     to "AAAAAgAAAJcAAAAuAw==",
            TvKey.SETTINGS  to "AAAAAgAAAJcAAAA2Aw==",
            TvKey.SOURCE    to "AAAAAQAAAAEAAABkAw==",
            TvKey.VOL_UP    to "AAAAAQAAAAEAAAASAw==",
            TvKey.VOL_DOWN  to "AAAAAQAAAAEAAAATAw==",
            TvKey.MUTE      to "AAAAAQAAAAEAAAAUAw==",
            TvKey.CH_UP     to "AAAAAQAAAAEAAAAQAw==",
            TvKey.CH_DOWN   to "AAAAAQAAAAEAAAARAw==",
            TvKey.PLAY      to "AAAAAgAAAJcAAAAaAw==",
            TvKey.PAUSE     to "AAAAAgAAAJcAAAAZAw==",
            TvKey.STOP      to "AAAAAgAAAJcAAAAYAw==",
            TvKey.FF        to "AAAAAgAAAJcAAAAcAw==",
            TvKey.RW        to "AAAAAgAAAJcAAAAbAw==",
            TvKey.NEXT      to "AAAAAgAAAJcAAAA9Aw==",
            TvKey.PREV      to "AAAAAgAAAJcAAAA8Aw==",
            TvKey.RED       to "AAAAAgAAAJcAAAAlAw==",
            TvKey.GREEN     to "AAAAAgAAAJcAAAAmAw==",
            TvKey.YELLOW    to "AAAAAgAAAJcAAAAnAw==",
            TvKey.BLUE      to "AAAAAgAAAJcAAAAkAw==",
            TvKey.NUM_0     to "AAAAAgAAAJcAAAEAAw==",
            TvKey.NUM_1     to "AAAAAgAAAJcAAAEBAw==",
            TvKey.NUM_2     to "AAAAAgAAAJcAAAECAw==",
            TvKey.NUM_3     to "AAAAAgAAAJcAAAEDAw==",
            TvKey.NUM_4     to "AAAAAgAAAJcAAAEEAw==",
            TvKey.NUM_5     to "AAAAAgAAAJcAAAEFAw==",
            TvKey.NUM_6     to "AAAAAgAAAJcAAAEGAw==",
            TvKey.NUM_7     to "AAAAAgAAAJcAAAEHAw==",
            TvKey.NUM_8     to "AAAAAgAAAJcAAAEIAw==",
            TvKey.NUM_9     to "AAAAAgAAAJcAAAEJAw==",
            TvKey.HDMI_1    to "AAAAAgAAABoAAABaAw==",
            TvKey.HDMI_2    to "AAAAAgAAABoAAABbAw==",
            TvKey.HDMI_3    to "AAAAAgAAABoAAABcAw==",
            TvKey.HDMI_4    to "AAAAAgAAABoAAABdAw==",
            TvKey.NETFLIX   to "AAAAAgAAABoAAAB8Aw==",
            TvKey.YOUTUBE   to "AAAAAgAAAMQAAABHAw==",
        )

        private val SOAP_TYPE = "text/xml; charset=utf-8".toMediaType()
        private val JSON_TYPE = "application/json".toMediaType()
    }

    private val insecureBaseUrl get() = "http://${device.ip}:${device.port}"
    private val secureBaseUrl get() = "https://${device.ip}:${device.port}"
    @Volatile private var activeBaseUrl: String = secureBaseUrl

    // The PSK/auth cookie obtained after pairing, stored in-memory during session
    @Volatile private var liveToken: String? = device.token
    @Volatile private var connected = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true }           // Sony uses self-signed cert
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // TvController implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Connects without pairing — uses the stored PSK token from [device.token].
     * Verifies the token is valid by calling the system info endpoint.
     * Returns false if the token is missing or rejected.
     */
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val psk = liveToken
        if (psk.isNullOrEmpty()) {
            Log.w(TAG, "No PSK token stored, cannot connect without pairing")
            return@withContext false
        }
        val body = """{"method":"getSystemInformation","id":1,"params":[],"version":"1.0"}"""

        // Prefer HTTPS when the TV supports it.
        val secureConnected = runCatching {
            val response = postJson(secureBaseUrl, ENDPOINT_SYSTEM, body, psk)
            response != null && response.contains("\"result\"")
        }.getOrDefault(false)
        if (secureConnected) {
            activeBaseUrl = secureBaseUrl
            connected = true
            return@withContext true
        }

        if (!TransportSecurityPolicy.allowInsecureDeviceProtocol("Sony IRCC-IP over http://")) {
            connected = false
            return@withContext false
        }

        runCatching {
            val response = postJson(insecureBaseUrl, ENDPOINT_SYSTEM, body, psk)
            connected = response != null && response.contains("\"result\"")
            if (connected) {
                activeBaseUrl = insecureBaseUrl
            }
            connected
        }.getOrElse {
            Log.e(TAG, "connect() failed", it)
            connected = false
            false
        }
    }

    /**
     * Full Sony IRCC-IP pairing flow.
     * Steps:
     *   1. POST /sony/accessControl/actRegister → TV shows 4-digit PIN
     *   2. Wait for user to enter PIN via [pinChannel]
     *   3. POST again with Basic auth (base64("0000:<pin>"))
     *   4. Extract PSK from response cookie
     */
    override suspend fun pair(): Boolean = withContext(Dispatchers.IO) {
        onPairingState(PairingState.CONNECTING)
        try {
            val pairingBaseUrl = resolvePairingBaseUrl() ?: run {
                onPairingState(PairingState.IDLE)
                return@withContext false
            }

            // Step 1: Initiate registration
            val initBody = buildAccessRegisterPayload()
            val initRequest = Request.Builder()
                .url("$pairingBaseUrl$ENDPOINT_ACCESS")
                .post(initBody.toRequestBody(JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()

            val step1Response = runCatching { httpClient.newCall(initRequest).execute() }
                .getOrNull()
            step1Response?.close()

            // Step 1': Some models require HTTP 401 before showing PIN — that's expected
            // TV should now display 4-digit PIN on screen

            onPairingState(PairingState.WAITING_FOR_PIN)
            val pin = withTimeoutOrNull(120_000L) { pinChannel.receive() }
            if (pin == null) {
                Log.w(TAG, "PIN entry timed out")
                onPairingState(PairingState.IDLE)
                return@withContext false
            }

            // Step 2: Complete registration with PIN as Basic auth credential
            // Sony expects: Authorization: Basic base64("0000:<pin>")
            val credentials = android.util.Base64.encodeToString(
                "0000:$pin".toByteArray(),
                android.util.Base64.NO_WRAP
            )
            val authRequest = Request.Builder()
                .url("$pairingBaseUrl$ENDPOINT_ACCESS")
                .post(initBody.toRequestBody(JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Basic $credentials")
                .build()

            val authResponse = runCatching { httpClient.newCall(authRequest).execute() }
                .getOrNull()

            if (authResponse == null || !authResponse.isSuccessful) {
                Log.w(TAG, "Pairing auth step failed: HTTP ${authResponse?.code}")
                authResponse?.close()
                onPairingState(PairingState.IDLE)
                return@withContext false
            }

            // Extract the Set-Cookie auth value
            val cookie = authResponse.header("Set-Cookie")
            val psk    = cookie?.split(";")
                ?.firstOrNull { it.trim().startsWith("auth=") }
                ?.substringAfter("auth=")?.trim()
            authResponse.close()

            if (!psk.isNullOrEmpty()) {
                liveToken = psk
                onTokenReceived(psk)
                activeBaseUrl = pairingBaseUrl
                connected = true
                onPairingState(PairingState.IDLE)
                Log.i(TAG, "Sony IRCC-IP pairing successful, PSK obtained")
                true
            } else {
                Log.w(TAG, "Pairing response did not contain a PSK cookie")
                onPairingState(PairingState.IDLE)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "pair() error", e)
            onPairingState(PairingState.IDLE)
            false
        }
    }

    override fun disconnect() {
        connected = false
    }

    override fun isConnected() = connected

    override fun getToken(): String? = liveToken
    override fun saveToken(token: String) { liveToken = token }

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val ircc = KEY_MAP[key] ?: run {
            Log.w(TAG, "No IRCC code for key $key")
            return@withContext
        }
        val psk = liveToken ?: return@withContext
        val soapBody = buildIrccSoap(ircc)
        runCatching {
            postSoap(activeBaseUrl, ENDPOINT_IRCC, soapBody, psk)
        }.onFailure {
            Log.e(TAG, "sendKey $key failed", it)
        }
    }

    override suspend fun sendText(text: String) {
        // Sony IRCC-IP does not support text input via IR codes.
        // Each character would require separate NUM key presses.
        Log.w(TAG, "sendText() not supported on Sony IRCC-IP protocol")
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val psk = liveToken ?: return@withContext emptyList()
        runCatching {
            val body = """{"method":"getApplicationList","id":60,"params":[],"version":"1.0"}"""
            val resp = postJson(activeBaseUrl, "/sony/appControl", body, psk) ?: return@withContext emptyList()
            val result = org.json.JSONObject(resp).optJSONArray("result")
                ?.optJSONArray(0) ?: return@withContext emptyList()
            (0 until result.length()).map { i ->
                val app = result.getJSONObject(i)
                val iconRaw = app.optString("icon").takeIf { it.isNotEmpty() }
                TvApp(
                    id      = app.optString("uri"),
                    name    = app.optString("title"),
                    iconUrl = normalizeIconUrl(activeBaseUrl, iconRaw)
                )
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
        val psk = liveToken ?: return@withContext
        runCatching {
            val body = """{"method":"setActiveApp","id":601,"params":[{"uri":"$appId"}],"version":"1.0"}"""
            postJson(activeBaseUrl, "/sony/appControl", body, psk)
        }.onFailure { Log.e(TAG, "launchApp $appId failed", it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun postJson(
        baseUrl: String,
        path: String,
        body: String,
        psk: String
    ): String? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(JSON_TYPE))
            .addHeader("X-Auth-PSK", psk)
            .addHeader("Content-Type", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "JSON request failed: path=$path code=${response.code}")
                return@use null
            }
            val bodyText = response.body?.string()
            if (bodyText.isNullOrBlank()) {
                Log.w(TAG, "JSON request returned empty body: path=$path")
                return@use null
            }
            bodyText
        }
    }

    private fun postSoap(
        baseUrl: String,
        path: String,
        body: String,
        psk: String
    ): String? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(SOAP_TYPE))
            .addHeader("X-Auth-PSK", psk)
            .addHeader("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "SOAP request failed: path=$path code=${response.code}")
                return@use null
            }
            response.body?.string()
        }
    }

    private fun buildIrccSoap(irccCode: String) = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
              <IRCCCode>$irccCode</IRCCCode>
            </u:X_SendIRCC>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildAccessRegisterPayload() = """
        {"id":13,"method":"actRegister","version":"1.0","params":[
            {"clientid":"UniRemote:android","nickname":"UniRemote"},
            [{"value":"yes","function":"WOL"}]
        ]}
    """.trimIndent()

    private fun resolvePairingBaseUrl(): String? {
        val secureInitSuccess = runCatching {
            val probeBody = buildAccessRegisterPayload()
            val probeRequest = Request.Builder()
                .url("$secureBaseUrl$ENDPOINT_ACCESS")
                .post(probeBody.toRequestBody(JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            httpClient.newCall(probeRequest).execute().use { response ->
                response.code in 200..499
            }
        }.getOrDefault(false)
        if (secureInitSuccess) {
            return secureBaseUrl
        }

        if (TransportSecurityPolicy.allowInsecureDeviceProtocol("Sony IRCC-IP over http://")) {
            return insecureBaseUrl
        }
        Log.w(TAG, "Sony pairing blocked: insecure fallback disabled and HTTPS unavailable")
        return null
    }
}

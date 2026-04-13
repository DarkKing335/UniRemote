package com.example.uniremote.network

import android.content.Context
import android.util.Log
import com.example.uniremote.BuildConfig
import com.example.uniremote.data.SecureCredentialStore
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "VizioTvController"

/**
 * Vizio SmartCast implementation.
 * Port: 7345 (HTTPS)
 * Note: Requires PIN pairing for first access.
 */
class VizioTvController(
    override val device: TvDevice,
    appContext: Context? = null
) : TvController {

    companion object {
        /**
         * Vizio SmartCast currently relies on permissive trust for self-signed certs.
         * Keep this controller disabled in production until proper certificate pinning is implemented.
         */
        fun supports(device: TvDevice): Boolean = BuildConfig.ENABLE_INSECURE_DEVICE_PROTOCOLS

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val baseUrl = "https://${device.ip}:7345"
    private var authToken: String? = device.token
    @Volatile private var connected = false
    @Volatile private var failureReason: String? = null
    private val secureStore = appContext?.let { SecureCredentialStore(it) }

    // Scoped per-controller HTTP client. No global TLS overrides are applied.
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) = Unit
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

        NetworkClient.instance.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        clearConnectionFailureReason()

        if (!supports(device)) {
            failureReason = "Vizio control is disabled in production until strict TLS pinning rollout is completed."
            connected = false
            return@withContext false
        }

        try {
            val request = Request.Builder()
                .url("$baseUrl/state/device/app/all")
                .apply {
                    if (!authToken.isNullOrBlank()) {
                        header("AUTH", authToken!!)
                    }
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!verifyPinnedTls(response)) {
                    connected = false
                    return@withContext false
                }
                connected = response.code == 200 || response.code == 401
                connected
            }
        } catch (e: Exception) {
            if (failureReason.isNullOrBlank()) {
                failureReason = "Failed TLS connection to Vizio TV. Check network or trusted certificate state."
            }
            Log.e(TAG, "Failed to connect to Vizio at ${device.ip}", e)
            connected = false
            false
        }
    }

    override fun disconnect() { connected = false }

    override fun isConnected(): Boolean = connected

    override suspend fun validateConnection(): Boolean = connect()

    override fun getConnectionFailureReason(): String? = failureReason

    override fun clearConnectionFailureReason() {
        failureReason = null
    }

    override suspend fun sendKey(key: TvKey) {
        if (!supports(device)) return
        val vizioKey = mapToVizioKey(key) ?: return
        put("/key_command/", vizioKey)
    }

    override suspend fun sendText(text: String) {
        if (!supports(device)) return
        // Vizio supports text injection via IME
        put("/ime/text_input", text)
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        if (!supports(device)) return@withContext emptyList()
        // Vizio apps are mostly web-based and listed in a specific payload
        emptyList() // Placeholder
    }

    override suspend fun launchApp(appId: String) {
        if (!supports(device)) return
        // POST /app/launch
    }

    private suspend fun put(path: String, value: String) = withContext(Dispatchers.IO) {
        if (!supports(device)) return@withContext
        try {
            val json = when {
                path.contains("key_command") -> "{\"KEYLIST\": [{\"CODESET\": 1, \"CODE\": $value, \"ACTION\": \"KEYPRESS\"}]}"
                else -> "{\"VALUE\": \"$value\"}"
            }

            val request = Request.Builder()
                .url("$baseUrl$path")
                .apply {
                    if (!authToken.isNullOrBlank()) {
                        header("AUTH", authToken!!)
                    }
                }
                .put(json.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (!verifyPinnedTls(response)) {
                    connected = false
                    return@withContext
                }
                Log.d(TAG, "PUT $path returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error PUTting to Vizio: $path", e)
        }
    }

    private fun mapToVizioKey(key: TvKey): String? = when (key) {
        TvKey.UP     -> "8"
        TvKey.DOWN   -> "12"
        TvKey.LEFT   -> "1"
        TvKey.RIGHT  -> "7"
        TvKey.OK     -> "2" // Select
        TvKey.BACK   -> "15"
        TvKey.HOME   -> "3"
        TvKey.VOL_UP -> "5"
        TvKey.VOL_DOWN -> "4"
        TvKey.MUTE   -> "0"
        TvKey.POWER  -> "2" // Depends on context
        else -> null
    }

    private fun verifyPinnedTls(response: Response): Boolean {
        val cert = response.handshake
            ?.peerCertificates
            ?.firstOrNull() as? X509Certificate
        if (cert == null) {
            failureReason = "TLS trust failed: no server certificate received from Vizio TV."
            return false
        }

        val store = secureStore
        if (store == null) {
            failureReason = "TLS trust store unavailable; cannot verify Vizio certificate pin."
            return false
        }

        val fingerprint = sha256Fingerprint(cert)
        val pinned = store.getVizioTlsPin(device.id)

        if (pinned.isNullOrBlank()) {
            store.putVizioTlsPin(device.id, fingerprint)
            Log.i(TAG, "Pinned Vizio certificate for ${device.name}: ${fingerprint.take(16)}...")
            return true
        }

        if (!pinned.equals(fingerprint, ignoreCase = true)) {
            failureReason =
                "TLS trust failed for ${device.name}: certificate changed. Possible MITM or TV cert reset. " +
                    "Forget and re-pair only if you trust this network."
            Log.e(TAG, "Vizio cert pin mismatch for ${device.id}. pinned=$pinned actual=$fingerprint")
            return false
        }

        return true
    }

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }.uppercase(Locale.US)
    }
}

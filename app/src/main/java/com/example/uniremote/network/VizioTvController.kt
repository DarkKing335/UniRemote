package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "VizioTvController"

/**
 * Vizio SmartCast implementation.
 * Port: 7345 (HTTPS)
 * Note: Requires PIN pairing for first access.
 */
class VizioTvController(override val device: TvDevice) : TvController {

    private val baseUrl = "https://${device.ip}:7345"
    private var authToken: String? = null // Should be persisted in real app

    init {
        setupUnsafeTrustManager()
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/state/device/app/all")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            val code = conn.responseCode
            code == 200 || code == 401 // 401 means it's alive but needs pairing
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Vizio at ${device.ip}", e)
            false
        }
    }

    override fun disconnect() {}

    override fun isConnected(): Boolean = true

    override suspend fun sendKey(key: TvKey) {
        val vizioKey = mapToVizioKey(key) ?: return
        put("/key_command/", vizioKey)
    }

    override suspend fun sendText(text: String) {
        // Vizio supports text injection via IME
        put("/ime/text_input", text)
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        // Vizio apps are mostly web-based and listed in a specific payload
        emptyList() // Placeholder
    }

    override suspend fun launchApp(appId: String) {
        // POST /app/launch
    }

    private suspend fun put(path: String, value: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl$path")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            if (authToken != null) {
                conn.setRequestProperty("AUTH", authToken)
            }
            conn.doOutput = true
            
            val json = when {
                path.contains("key_command") -> "{\"KEYLIST\": [{\"CODESET\": 1, \"CODE\": $value, \"ACTION\": \"KEYPRESS\"}]}"
                else -> "{\"VALUE\": \"$value\"}"
            }
            
            conn.outputStream.use { it.write(json.toByteArray()) }
            Log.d(TAG, "PUT $path returned ${conn.responseCode}")
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

    /**
     * Vizio TVs usually use self-signed certs.
     * In a production app, we should pin the specific cert.
     */
    private fun setupUnsafeTrustManager() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate>? = null
            override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
        })

        try {
            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup unsafe trust manager", e)
        }
    }
}

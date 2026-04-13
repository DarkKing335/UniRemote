package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "PanasonicController"

/**
 * Controller for Panasonic Viera TVs using the Viera Remote (SOAP/HTTP) protocol.
 * Usually port 55000.
 */
class PanasonicTvController(override val device: TvDevice) : TvController {

    private val client = OkHttpClient()
    private val mediaType = "text/xml; charset=utf-8".toMediaType()

    companion object {
        private val KEY_MAP = mapOf(
            TvKey.UP to "NRC_UP-ON",
            TvKey.DOWN to "NRC_DOWN-ON",
            TvKey.LEFT to "NRC_LEFT-ON",
            TvKey.RIGHT to "NRC_RIGHT-ON",
            TvKey.OK to "NRC_ENTER-ON",
            TvKey.BACK to "NRC_RETURN-ON",
            TvKey.HOME to "NRC_HOME-ON",
            TvKey.MENU to "NRC_MENU-ON",
            TvKey.VOL_UP to "NRC_VOLUP-ON",
            TvKey.VOL_DOWN to "NRC_VOLDOWN-ON",
            TvKey.MUTE to "NRC_MUTE-ON",
            TvKey.CH_UP to "NRC_CH_UP-ON",
            TvKey.CH_DOWN to "NRC_CH_DOWN-ON",
            TvKey.POWER to "NRC_POWER-ON",
            TvKey.RED to "NRC_RED-ON",
            TvKey.GREEN to "NRC_GREEN-ON",
            TvKey.YELLOW to "NRC_YELLOW-ON",
            TvKey.BLUE to "NRC_BLUE-ON",
            TvKey.NUM_0 to "NRC_D0-ON",
            TvKey.NUM_1 to "NRC_D1-ON",
            TvKey.NUM_2 to "NRC_D2-ON",
            TvKey.NUM_3 to "NRC_D3-ON",
            TvKey.NUM_4 to "NRC_D4-ON",
            TvKey.NUM_5 to "NRC_D5-ON",
            TvKey.NUM_6 to "NRC_D6-ON",
            TvKey.NUM_7 to "NRC_D7-ON",
            TvKey.NUM_8 to "NRC_D8-ON",
            TvKey.NUM_9 to "NRC_D9-ON"
        )
    }

    override suspend fun connect(): Boolean = true // Stateless

    override fun disconnect() {}

    override fun isConnected(): Boolean = true

    override suspend fun sendKey(key: TvKey) {
        val panasonicKey = KEY_MAP[key] ?: return
        sendSoapRequest("X_SendKey", "<X_KeyEvent>$panasonicKey</X_KeyEvent>")
    }

    override suspend fun sendText(text: String) {
        // Panasonic SOAP doesn't have a reliable way to send full strings easily
    }

    override suspend fun getInstalledApps(): List<TvApp> = emptyList()

    override suspend fun launchApp(appId: String) {}

    private suspend fun sendSoapRequest(action: String, body: String) = withContext(Dispatchers.IO) {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:$action xmlns:u="urn:panasonic-com:service:p00NetworkControl:1">
                        $body
                    </u:$action>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val url = "http://${device.ip}:55000/nrc/control_0"
        val request = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"urn:panasonic-com:service:p00NetworkControl:1#$action\"")
            .post(soapBody.toRequestBody(mediaType))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "SOAP request failed: ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending SOAP request", e)
        }
    }
}

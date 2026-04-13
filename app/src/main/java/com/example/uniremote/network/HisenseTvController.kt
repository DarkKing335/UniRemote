package com.example.uniremote.network

import com.example.uniremote.data.TvDevice

/**
 * Controller for Hisense TVs (VIDAA OS).
 * Note: Many Hisense TVs use MQTT or a specialized SSL protocol on port 36666.
 * This is a basic implementation placeholder.
 */
class HisenseTvController(override val device: TvDevice) : TvController {
    companion object {
        /** Stub controller: disabled until VIDAA protocol implementation is complete. */
        fun supports(device: TvDevice): Boolean = false
    }

    override suspend fun connect(): Boolean = false
    override fun disconnect() {}
    override fun isConnected(): Boolean = false
    override suspend fun validateConnection(): Boolean = false
    override suspend fun sendKey(key: TvKey) {
        // Implement VIDAA protocol here
    }
    override suspend fun sendText(text: String) {}
    override suspend fun getInstalledApps(): List<TvApp> = emptyList()
    override suspend fun launchApp(appId: String) {}
}

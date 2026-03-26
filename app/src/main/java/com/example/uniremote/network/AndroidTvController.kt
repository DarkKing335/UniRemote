package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvDevice
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AndroidTvController"

/**
 * Android TV controller using ADB-over-TCP (port 5555) shell commands.
 * Integrates the mobile-dev-inc `dadb` library for proper RSA authentication.
 *
 * Requirements on TV side:
 *   Developer Options → USB Debugging ON
 *   Developer Options → ADB over network ON
 */
class AndroidTvController(
    override val device: TvDevice,
    private val keyPair: dadb.AdbKeyPair? = null
) : TvController {

    companion object {
        private val KEY_MAP = mapOf(
            TvKey.UP         to 19,
            TvKey.DOWN       to 20,
            TvKey.LEFT       to 21,
            TvKey.RIGHT      to 22,
            TvKey.OK         to 23,
            TvKey.BACK       to 4,
            TvKey.HOME       to 3,
            TvKey.MENU       to 82,
            TvKey.VOL_UP     to 24,
            TvKey.VOL_DOWN   to 25,
            TvKey.MUTE       to 164,
            TvKey.CH_UP      to 166,
            TvKey.CH_DOWN    to 167,
            TvKey.POWER      to 26,
            TvKey.PLAY       to 126,
            TvKey.PAUSE      to 127,
            TvKey.STOP       to 86,
            TvKey.FF         to 87,
            TvKey.RW         to 89,
            TvKey.NEXT       to 87,
            TvKey.PREV       to 88,
            TvKey.NUM_0      to 7,
            TvKey.NUM_1      to 8,
            TvKey.NUM_2      to 9,
            TvKey.NUM_3      to 10,
            TvKey.NUM_4      to 11,
            TvKey.NUM_5      to 12,
            TvKey.NUM_6      to 13,
            TvKey.NUM_7      to 14,
            TvKey.NUM_8      to 15,
            TvKey.NUM_9      to 16,
            TvKey.SOURCE     to 178,
            TvKey.INFO       to 165,
            TvKey.SETTINGS   to 176,
            TvKey.SEARCH     to 84,
            TvKey.SLEEP      to 223,
            TvKey.ANDROID_LAUNCHER to 3,
        )
    }

    private var dadb: Dadb? = null
    private var connected = false

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Force ADB port to 5555. mDNS often discovers port 6466 (RemoteV1), 8008 (DIAL), 
            // or 80/20060 (SDCP), but wireless ADB is almost always running on port 5555.
            val adbPort = if (device.port == 5555) 5555 else 5555

            // If keyPair is provided, Dadb handles the RSA auth handshake (prompting the TV if needed)
            // Protect against infinite socket hangs if TV drops packets silently
            dadb = kotlinx.coroutines.withTimeout(8000L) {
                if (keyPair != null) {
                    Dadb.create(device.ip, adbPort, keyPair)
                } else {
                    Dadb.create(device.ip, adbPort)
                }
            }
            // Verify connection works
            val response = dadb?.shell("echo uniremote_ok")
            connected = response?.output?.contains("uniremote_ok") == true
            connected
        }.onFailure { e ->
            Log.w(TAG, "ADB connect error: ${e.message}")
            connected = false
        }.getOrElse { false }
    }

    private fun shell(cmd: String) {
        if (!connected || dadb == null) return
        try {
            val res = dadb?.shell(cmd)
            Log.d(TAG, "Shell: $cmd -> exitCode: ${res?.exitCode}")
        } catch (e: Exception) {
            Log.e(TAG, "Shell cmd failed: $cmd", e)
            connected = false
        }
    }

    override fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
        connected = false
    }

    override fun isConnected() = connected

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val keyCode = KEY_MAP[key] ?: return@withContext
        shell("input keyevent $keyCode")
    }

    override suspend fun sendText(text: String): Unit = withContext(Dispatchers.IO) {
        val safeText = text.replace("'", "'\\''").replace(" ", "%s")
        shell("input text '$safeText'")
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        runCatching {
            val output = dadb?.shell("pm list packages -3 -f")?.output ?: ""
            val lines = output.split("\n", "\r")
            
            lines.mapNotNull { l ->
                val parts = l.trim().removePrefix("package:").split("=")
                if (parts.size == 2) {
                    val pkg = parts[1].trim()
                    TvApp(id = pkg, name = pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() })
                } else null
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun launchApp(appId: String): Unit = withContext(Dispatchers.IO) {
        shell("monkey -p $appId -c android.intent.category.LAUNCHER 1")
    }

    override suspend fun moveMouse(dx: Float, dy: Float): Unit = withContext(Dispatchers.IO) {
        shell("input roll $dx $dy")
    }

    override suspend fun tapMouse(): Unit = withContext(Dispatchers.IO) {
        shell("input keyevent 23")
    }
}


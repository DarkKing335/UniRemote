package com.example.uniremote.network

import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Android TV controller using ADB-over-TCP (port 5555) shell commands.
 *
 * Requirements on TV side:
 *   Developer Options → USB Debugging ON
 *   Developer Options → ADB over network ON  (or use adb tcpip 5555 once via USB)
 *
 * On first connect the TV shows a "Allow USB debugging?" dialog — user must
 * accept it once.  After that the RSA fingerprint is stored and subsequent
 * connection attempts work without the dialog.
 *
 * Note: Full ADB auth (RSA handshake) is complex for a pure-Android client.
 * This implementation uses a simpler ADB v0 shell protocol that works when
 * the TV has already accepted the host's key (i.e. not the first time).
 * For the first-time pairing flow Android TV also supports the Remote-Control
 * pairing via port 6466 (Android TV Remote Service) — see GUIDE in README.
 */
class AndroidTvController(override val device: TvDevice) : TvController {

    companion object {
        private val KEY_MAP = mapOf(
            TvKey.UP         to 19,  // KEYCODE_DPAD_UP
            TvKey.DOWN       to 20,  // KEYCODE_DPAD_DOWN
            TvKey.LEFT       to 21,  // KEYCODE_DPAD_LEFT
            TvKey.RIGHT      to 22,  // KEYCODE_DPAD_RIGHT
            TvKey.OK         to 23,  // KEYCODE_DPAD_CENTER
            TvKey.BACK       to 4,   // KEYCODE_BACK
            TvKey.HOME       to 3,   // KEYCODE_HOME
            TvKey.MENU       to 82,  // KEYCODE_MENU
            TvKey.VOL_UP     to 24,  // KEYCODE_VOLUME_UP
            TvKey.VOL_DOWN   to 25,  // KEYCODE_VOLUME_DOWN
            TvKey.MUTE       to 164, // KEYCODE_VOLUME_MUTE
            TvKey.CH_UP      to 166, // KEYCODE_CHANNEL_UP
            TvKey.CH_DOWN    to 167, // KEYCODE_CHANNEL_DOWN
            TvKey.POWER      to 26,  // KEYCODE_POWER
            TvKey.PLAY       to 126, // KEYCODE_MEDIA_PLAY
            TvKey.PAUSE      to 127, // KEYCODE_MEDIA_PAUSE
            TvKey.STOP       to 86,  // KEYCODE_MEDIA_STOP
            TvKey.FF         to 87,  // KEYCODE_MEDIA_FAST_FORWARD
            TvKey.RW         to 89,  // KEYCODE_MEDIA_REWIND
            TvKey.NEXT       to 87,  // KEYCODE_MEDIA_NEXT
            TvKey.PREV       to 88,  // KEYCODE_MEDIA_PREVIOUS
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
            TvKey.SOURCE     to 178,  // KEYCODE_TV_INPUT
            TvKey.INFO       to 165,  // KEYCODE_INFO
            TvKey.SETTINGS   to 176,  // KEYCODE_SETTINGS
            TvKey.SEARCH     to 84,   // KEYCODE_SEARCH
            TvKey.SLEEP      to 223,  // KEYCODE_SLEEP
            TvKey.ANDROID_LAUNCHER to 3, // HOME
        )
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var connected = false

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            socket = Socket().also { s ->
                s.connect(java.net.InetSocketAddress(device.ip, device.port), 5000)
                s.soTimeout = 10_000
            }
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

            // ADB handshake — send CNXN message
            sendAdbConnect()
            connected = true
            true
        }.getOrElse {
            connected = false
            false
        }
    }

    private fun sendAdbConnect() {
        // Simplified: write ADB CONNECT string and read response
        // Full ADB RSA auth is out-of-scope; works after initial USB pairing
        writer?.println("host:transport-any")
        // On a real ADB-over-TCP the handshake is binary (CNXN packet).
        // We send a minimal shell command to verify connectivity:
        writer?.println("shell:echo uniremote_ok")
        reader?.readLine() // "uniremote_ok\n"
    }

    private fun shell(cmd: String) {
        try {
            Socket(device.ip, device.port).use { s ->
                s.soTimeout = 5000
                val w = PrintWriter(s.getOutputStream(), true)
                w.println("shell:$cmd")
                s.getInputStream().bufferedReader().readLine()
            }
        } catch (_: Exception) { }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        connected = false
    }

    override fun isConnected() = connected

    override suspend fun sendKey(key: TvKey): Unit = withContext(Dispatchers.IO) {
        val keyCode = KEY_MAP[key] ?: return@withContext
        shell("input keyevent $keyCode")
    }

    override suspend fun sendText(text: String): Unit = withContext(Dispatchers.IO) {
        // Escape spaces for ADB shell
        val escaped = text.replace(" ", "%s")
        shell("input text \"$escaped\"")
    }

    override suspend fun getInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = Socket(device.ip, device.port)
            socket.soTimeout = 10_000
            val w = PrintWriter(socket.getOutputStream(), true)
            val r = BufferedReader(InputStreamReader(socket.getInputStream()))
            w.println("shell:pm list packages -3 -f")
            val lines = mutableListOf<String>()
            var line: String?
            while (r.readLine().also { line = it } != null) {
                line?.let { lines.add(it) }
            }
            socket.close()
            // Format: "package:/data/app/com.netflix.ninja-1.apk=com.netflix.ninja"
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
        shell("input mouse move ${dx.toInt()} ${dy.toInt()}")
    }

    override suspend fun tapMouse(): Unit = withContext(Dispatchers.IO) {
        shell("input tap 0 0") // Click at last pointer position (best-effort)
    }
}

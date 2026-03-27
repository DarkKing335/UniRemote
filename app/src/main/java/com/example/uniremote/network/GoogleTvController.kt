package com.example.uniremote.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.*
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

private const val TAG          = "GoogleTvController"
private const val KEY_ALIAS    = "uniremote_google_tv"
private const val PAIR_PORT    = 6467
private const val CONTROL_PORT = 6466
private const val SERVICE_NAME = "uniremote"
private const val DEVICE_NAME  = "UniRemote"

// ── Pairing state ─────────────────────────────────────────────────────────────
enum class PairingState { IDLE, CONNECTING, WAITING_FOR_PIN, VERIFYING_PIN, PAIRED, FAILED }

// ── Internal control commands ─────────────────────────────────────────────────
private sealed class CtrlCmd {
    data class Key(val code: Int) : CtrlCmd()
    object Exit : CtrlCmd()
}

// ─────────────────────────────────────────────────────────────────────────────
class GoogleTvController(
    override val device: TvDevice,
    val onPairingState: (PairingState) -> Unit = {}
) : TvController {

    // ── Key map (Android KEYCODE values) ──────────────────────────────────────
    companion object {
        val KEY_MAP = mapOf(
            TvKey.UP       to 19,  TvKey.DOWN     to 20,  TvKey.LEFT  to 21,
            TvKey.RIGHT    to 22,  TvKey.OK        to 23,  TvKey.BACK  to 4,
            TvKey.HOME     to 3,   TvKey.MENU      to 82,  TvKey.EXIT  to 4,
            TvKey.VOL_UP   to 24,  TvKey.VOL_DOWN  to 25,  TvKey.MUTE  to 164,
            TvKey.CH_UP    to 166, TvKey.CH_DOWN   to 167,
            TvKey.POWER    to 26,
            TvKey.PLAY     to 126, TvKey.PAUSE     to 127, TvKey.STOP  to 86,
            TvKey.FF       to 90,  TvKey.RW        to 89,
            TvKey.NEXT     to 87,  TvKey.PREV      to 88,
            TvKey.NUM_0    to 7,   TvKey.NUM_1     to 8,   TvKey.NUM_2 to 9,
            TvKey.NUM_3    to 10,  TvKey.NUM_4     to 11,  TvKey.NUM_5 to 12,
            TvKey.NUM_6    to 13,  TvKey.NUM_7     to 14,  TvKey.NUM_8 to 15,
            TvKey.NUM_9    to 16,
            TvKey.SOURCE   to 178, TvKey.INFO      to 165, TvKey.SETTINGS to 176,
            TvKey.SEARCH   to 84,  TvKey.SLEEP     to 223,
            TvKey.ANDROID_LAUNCHER to 3,
            TvKey.NETFLIX  to 126, TvKey.YOUTUBE   to 126,
        )
    }

    private val cmdChannel  = Channel<CtrlCmd>(Channel.UNLIMITED)
    private val pinChannel  = Channel<String>(Channel.RENDEZVOUS)
    private var controlJob: Job? = null
    private var connected   = false

    // ── SSL / KeyStore ────────────────────────────────────────────────────────

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateSubject(X500Principal("CN=atvremote, O=Google Inc., C=US"))
            .setCertificateNotBefore(java.util.Date())
            .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            .apply { initialize(spec) }.generateKeyPair()
        Log.i(TAG, "RSA key pair generated in Android KeyStore")
    }

    private fun buildSslContext(): SSLContext {
        ensureKeyPair()
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            private var pinnedCertHash: String? = null
            
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                if (c.isEmpty()) throw CertificateException("No certificate presented")
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(c[0].encoded).joinToString("") { "%02x".format(it) }
                    
                if (pinnedCertHash == null) {
                    pinnedCertHash = hash
                } else if (pinnedCertHash != hash) {
                    throw CertificateException("Certificate pinning mismatch")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, trustAll, SecureRandom()) }
    }

    private fun openSslSocket(port: Int): SSLSocket {
        val sock = buildSslContext().socketFactory.createSocket() as SSLSocket
        sock.connect(InetSocketAddress(device.ip, port), 10_000)
        sock.soTimeout = 10_000
        sock.startHandshake()
        return sock
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    /** BigInteger → unsigned byte array (strip leading 0x00 Java prepends) */
    private fun bigBytes(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return if (raw[0] == 0.toByte() && raw.size > 1) raw.copyOfRange(1, raw.size) else raw
    }

    /**
     * Encode the pairing secret per the Google TV protocol:
     * SHA-256(client_mod | client_exp | server_mod | server_exp | code_bytes)
     * where code_bytes = 2 bytes decoded from hex chars [2..5] of the 6-char PIN.
     */
    private fun encodeSecret(sock: SSLSocket, pin: String): ByteArray {
        val ks     = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val cPub   = (ks.getCertificate(KEY_ALIAS) as X509Certificate).publicKey as RSAPublicKey
        val sPub   = (sock.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bigBytes(cPub.modulus));         update(bigBytes(cPub.publicExponent))
            update(bigBytes(sPub.modulus));         update(bigBytes(sPub.publicExponent))
            // PIN is 6 hex chars e.g. "A3F19C" → take chars 2..5 → 2 bytes
            update(pin.substring(2, 6).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        return digest.digest()
    }

    // ── Message framing ───────────────────────────────────────────────────────

    private fun send(sock: SSLSocket, payload: ByteArray) {
        sock.outputStream.write(payload.size)
        sock.outputStream.write(payload)
        sock.outputStream.flush()
    }

    private fun recv(sock: SSLSocket): ByteArray {
        val size = sock.inputStream.read().also { if (it < 0) throw java.io.IOException("closed") }
        val buf  = ByteArray(size)
        var n    = 0
        while (n < size) n += sock.inputStream.read(buf, n, size - n).also { if (it < 0) throw java.io.IOException("closed") }
        return buf
    }

    // ── Pairing messages (port 6467) ──────────────────────────────────────────

    private fun msgPairRequest(): ByteArray {
        val svc = SERVICE_NAME.toByteArray(); val dev = DEVICE_NAME.toByteArray()
        return byteArrayOf(
            0x08, 0x02, 0x10, (-56).toByte(), 0x01,           // version + status OK
            0x52, (svc.size + dev.size + 6).toByte(),          // PAIRING_MESSAGE + length
            0x0A, svc.size.toByte(), *svc,
            0x12, dev.size.toByte(), *dev
        )
    }

    private fun msgOption()  = byteArrayOf(
        0x08, 0x02, 0x10, (-56).toByte(), 0x01,
        0xA2.toByte(), 0x01, 0x08, 0x0A, 0x04,
        0x08, 0x03, 0x10, 0x06, 0x18, 0x01         // HEX encoding, ROLE_INPUT
    )

    private fun msgConfig()  = byteArrayOf(
        0x08, 0x02, 0x10, (-56).toByte(), 0x01,
        0xF2.toByte(), 0x01, 0x08, 0x0A, 0x04,
        0x08, 0x03, 0x10, 0x06, 0x10, 0x01
    )

    private fun msgSecret(sock: SSLSocket, pin: String): ByteArray {
        val sec = encodeSecret(sock, pin)
        return byteArrayOf(
            0x08, 0x02, 0x10, (-56).toByte(), 0x01,
            0xC2.toByte(), 0x01, 0x02,
            0x22, 0x0A, sec.size.toByte(), *sec
        )
    }

    // ── Control messages (port 6466) ──────────────────────────────────────────

    private fun msgAppInfo(): ByteArray {
        val name = "uniremote".toByteArray(); val ver = "1.0.0".toByteArray()
        val tags = byteArrayOf(0x01, 0x31, 0x2A, name.size.toByte(), *name, 0x32, ver.size.toByte(), *ver)
        return byteArrayOf(0x0A, (tags.size + 8).toByte(), 0x08, (-18).toByte(), 0x04, 0x12,
            (tags.size + 3).toByte(), 0x18, 0x01, 0x22, *tags)
    }

    private fun msgAck()    = byteArrayOf(0x12, 0x03, 0x08, (-18).toByte(), 0x04)
    private fun msgPong()   = byteArrayOf(0x4A, 0x02, 0x08, 0x19)
    private fun msgKey(k: Int) = byteArrayOf(0x52, 0x04, 0x08, k.toByte(), 0x10, 0x03)

    // ── Pairing flow ──────────────────────────────────────────────────────────

    /** Called by ViewModel when the user submits the 6-char PIN shown on TV. */
    suspend fun submitPin(pin: String) = pinChannel.send(pin)

    /**
     * Runs the full pairing handshake on port 6467.
     * Suspends at WAITING_FOR_PIN until [submitPin] is called.
     */
    suspend fun pair(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            onPairingState(PairingState.CONNECTING)
            val sock = openSslSocket(PAIR_PORT)

            send(sock, msgPairRequest());  recv(sock)   // state 0 → 1
            send(sock, msgOption());       recv(sock)   // state 1 → 2
            send(sock, msgConfig());       recv(sock)   // state 2 → 3

            onPairingState(PairingState.WAITING_FOR_PIN)
            val pin = withTimeoutOrNull(120_000L) { pinChannel.receive() }
            if (pin == null) { sock.close(); onPairingState(PairingState.FAILED); return@withContext false }

            onPairingState(PairingState.VERIFYING_PIN)
            send(sock, msgSecret(sock, pin))
            val resp = runCatching { recv(sock) }.getOrElse { byteArrayOf() }
            sock.close()

            // A non-empty response with no error byte means success
            val ok = resp.isNotEmpty()
            onPairingState(if (ok) PairingState.PAIRED else PairingState.FAILED)
            Log.i(TAG, if (ok) "Pairing OK" else "Pairing FAILED: ${resp.toList()}")
            ok
        }.getOrElse { e ->
            Log.e(TAG, "pair() exception: ${e.message}", e)
            onPairingState(PairingState.FAILED); false
        }
    }

    // ── TvController: connect / control ───────────────────────────────────────

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sock = openSslSocket(CONTROL_PORT)
            controlJob = CoroutineScope(Dispatchers.IO).launch { runSession(sock) }
            connected = true
            Log.i(TAG, "Control session started: ${device.ip}:$CONTROL_PORT")
            true
        }.getOrElse { e -> Log.e(TAG, "connect failed: ${e.message}"); false }
    }

    private suspend fun CoroutineScope.runSession(sock: SSLSocket) {
        try {
            // State 5: consume initial TV message
            sock.soTimeout = 5_000
            runCatching { recv(sock) }

            // State 6: send app info, wait for ACK (0x12)
            sock.soTimeout = 10_000
            send(sock, msgAppInfo())
            awaitTag(sock, 0x12)

            // State 7: send ack, wait for tags 0xC2, 0xA2, 0x92
            send(sock, msgAck())
            awaitTags(sock, mutableListOf(0xC2, 0xA2, 0x92))

            Log.i(TAG, "Google TV READY")

            // State 9: Separation of Read and Write pipelines
            sock.soTimeout = 0 // Blocking read

            val receiverJob = launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val msg = recv(sock)
                        if (msg.isNotEmpty() && msg[0].toInt() and 0xFF == 0x42) send(sock, msgPong())
                    } catch (e: java.net.SocketException) {
                        break
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            while (isActive) {
                val cmd = cmdChannel.receive() // Suspends efficiently
                when (cmd) {
                    is CtrlCmd.Key -> send(sock, msgKey(cmd.code))
                    CtrlCmd.Exit   -> {
                        receiverJob.cancel()
                        break
                    }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Log.e(TAG, "Session error: ${e.message}") }
        finally { connected = false; runCatching { sock.close() } }
    }

    /** Wait until a message with [expectedTag] arrives; respond to pings. */
    private fun awaitTag(sock: SSLSocket, expectedTag: Int) {
        repeat(30) {
            val m = runCatching { recv(sock) }.getOrNull() ?: return
            if (m.isEmpty()) return@repeat
            val t = m[0].toInt() and 0xFF
            if (t == 0x42) send(sock, msgPong())
            if (t == expectedTag) return
        }
    }

    /** Wait until all [tags] have been received; respond to pings. */
    private fun awaitTags(sock: SSLSocket, tags: MutableList<Int>) {
        while (tags.isNotEmpty()) {
            val m = runCatching { recv(sock) }.getOrNull() ?: break
            if (m.isEmpty()) continue
            val t = m[0].toInt() and 0xFF
            if (t == 0x42) send(sock, msgPong())
            tags.remove(t)
        }
    }

    // ── TvController interface ────────────────────────────────────────────────

    override fun disconnect() {
        cmdChannel.trySend(CtrlCmd.Exit)
        controlJob?.cancel(); controlJob = null
        connected = false
    }

    override fun isConnected() = connected

    override suspend fun sendKey(key: TvKey) {
        val code = KEY_MAP[key] ?: run {
            Log.w(TAG, "No mapping for $key")
            return
        }
        cmdChannel.trySend(CtrlCmd.Key(code))
    }

    override suspend fun sendText(text: String) {
        for (ch in text) {
            val code = when {
                ch in '0'..'9' -> 7 + (ch - '0')
                ch in 'a'..'z' -> 29 + (ch - 'a')
                ch in 'A'..'Z' -> 29 + (ch - 'A')
                ch == ' '      -> 62
                ch == '\n'     -> 66
                ch == '\b'     -> 67
                else           -> continue
            }
            cmdChannel.trySend(CtrlCmd.Key(code))
            delay(50)
        }
    }

    override suspend fun getInstalledApps(): List<TvApp> = emptyList()

    override suspend fun launchApp(appId: String) {
        throw UnsupportedOperationException("Google TV Remote Protocol không hỗ trợ mở app trực tiếp.")
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("Google TV Remote Protocol không hỗ trợ chuột. Dùng tab D-Pad.")
    }

    override suspend fun tapMouse() {
        throw UnsupportedOperationException("Google TV Remote Protocol không hỗ trợ chuột. Dùng tab D-Pad.")
    }
}

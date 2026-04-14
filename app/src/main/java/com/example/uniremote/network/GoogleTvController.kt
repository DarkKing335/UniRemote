package com.example.uniremote.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

private const val TAG          = "GoogleTvController"
private const val PAIR_PORT    = 6467
private const val CONTROL_PORT = 6466
private const val SERVICE_NAME = "uniremote"
private const val DEVICE_NAME  = "UniRemote"

// ── Pairing state ─────────────────────────────────────────────────────────────
enum class PairingState { IDLE, CONNECTING, WAITING_FOR_PIN, VERIFYING_PIN, PAIRED, FAILED }

// ── Internal control commands ─────────────────────────────────────────────────
private sealed class CtrlCmd {
    data class Payload(val bytes: ByteArray) : CtrlCmd()
    object Exit : CtrlCmd()
}

// ─────────────────────────────────────────────────────────────────────────────
class GoogleTvController(
    override val device: TvDevice,
    val onPairingState: (PairingState) -> Unit = {},
    val onUnexpectedDisconnect: (String) -> Unit = {}
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
            TvKey.GUIDE    to 172,
            TvKey.SEARCH   to 84,  TvKey.SLEEP     to 223,
            TvKey.ANDROID_LAUNCHER to 3,
            // Netflix/YouTube are handled via launchApp() — no keycode equivalent
        )

        // Pairing success detection has moved to PairingProto.containsStatusOk()
        // and PairingProto.parseStatus(). See PairingProto.kt.
    }

    // Bounded with DROP_OLDEST: if the socket is slow and the user holds a key, we keep
    // only the 8 most-recent commands — prevents a runaway queue of 100+ events.
    private val cmdChannel  = Channel<CtrlCmd>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pinChannel  = Channel<String>(Channel.RENDEZVOUS)
    private var controlJob: Job? = null
    // @Volatile: written from control session coroutine, read from VM coroutines
    @Volatile private var connected = false
    @Volatile private var manualDisconnectRequested = false
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0

    // Controller-owned scope for the session loop — cancelled in disconnect().
    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Certificate hash pinned per controller instance (persists across reconnect)
    @Volatile private var pinnedCertHash: String? = null


    // ── SSL / Software KeyStore ───────────────────────────────────────────────
    //
    // WHY NOT AndroidKeyStore:
    //   Conscrypt's TLS client-auth path calls CryptoUpcalls.rsaSignDigestWithPrivateKey()
    //   which internally uses Cipher.ENCRYPT_MODE with the private key. AndroidKeyStore
    //   hardware-backed keys are non-extractable and have NO Keymaster mapping for
    //   "private key in ENCRYPT mode" — it maps to PURPOSE_SIGN/DECRYPT, neither of
    //   which Conscrypt uses via Cipher. This causes "Incompatible padding mode" on
    //   every TLS handshake regardless of key spec.
    //
    // SOLUTION: Software RSA-2048 key pair (standard JCE, not hardware-backed).
    //   • Conscrypt uses it natively via the OpenSSL RSA engine — no CryptoUpcalls needed.
    //   • Key material is persisted in app-private SharedPreferences.
    //   • Equivalent to what Python (PEM file), Go (key file), and ESP32 (raw bytes) all do.

    private fun buildSslContext(): SSLContext {
        val (privKey, cert) = SoftwareTlsKey.getOrCreate()

        val customKM = object : X509ExtendedKeyManager() {
            private val ALIAS = "sw_client"
            override fun chooseClientAlias(
                keyType: Array<out String>?, issuers: Array<out Principal>?,
                socket: Socket?
            ): String = ALIAS
            override fun getClientAliases(
                keyType: String?, issuers: Array<out Principal>?
            ): Array<String> = arrayOf(ALIAS)
            override fun chooseServerAlias(
                keyType: String?, issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null
            override fun getServerAliases(
                keyType: String?, issuers: Array<out Principal>?
            ): Array<String>? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = privKey
        }

        // Trust-on-first-use cert pinning for the TV server certificate.
        val trustPinned = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                if (c.isEmpty()) throw CertificateException("TV sent no certificate")
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(c[0].encoded).joinToString("") { "%02x".format(it) }
                val pinned = pinnedCertHash
                if (pinned == null) {
                    pinnedCertHash = hash
                    Log.d(TAG, "TV cert pinned: ${hash.take(16)}...")
                } else if (pinned != hash) {
                    throw CertificateException("TV cert mismatch (pinning)")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        return SSLContext.getInstance("TLSv1.2").also {
            it.init(arrayOf(customKM), trustPinned, SecureRandom())
        }
    }

    private fun openSslSocket(port: Int): SSLSocket {
        val sock = buildSslContext().socketFactory.createSocket() as SSLSocket
        try {
            sock.connect(InetSocketAddress(device.ip, port), 10_000)
            sock.soTimeout = 10_000
            sock.startHandshake()
            Log.d(TAG, "TLS handshake OK  port=$port  cipher=${sock.session.cipherSuite}")
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e  // re-throw so pair() / connect() see the real cause
        }
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
     *
     * [pin] is the 6-character alphanumeric code shown on the TV screen.
     * The protocol uses 2 bytes derived from positions 2–5 of the PIN:
     *   - If those 4 chars are valid hex (e.g. "A3C8") → parse as two hex bytes.
     *   - If the TV sends a purely numeric PIN (e.g. "123456") → treat as decimal
     *     bytes to avoid a NumberFormatException crash (digits 0–9 are valid hex too,
     *     but we validate explicitly to guard against chars like 'G' or 'Z').
     */
    private fun encodeSecret(sock: SSLSocket, pin: String): ByteArray {
        require(pin.length >= 6) { "Google TV PIN must be exactly 6 chars, got ${pin.length}" }
        // Use the SOFTWARE client cert public key (NOT AndroidKeyStore any more)
        val cPub   = SoftwareTlsKey.getOrCreate().second.publicKey as RSAPublicKey
        val sPub   = (sock.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey

        // Extract the 2-byte code from PIN positions 2..5
        val pinSub  = pin.substring(2, 6)
        val codeBytes: ByteArray = try {
            pinSub.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            // Fallback: interpret each pair as a decimal value clamped to 0..255
            Log.w(TAG, "PIN '$pin' contains non-hex chars; falling back to decimal byte encoding")
            pinSub.chunked(2).map { it.toIntOrNull()?.toByte() ?: 0 }.toByteArray()
        }

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bigBytes(cPub.modulus));         update(bigBytes(cPub.publicExponent))
            update(bigBytes(sPub.modulus));         update(bigBytes(sPub.publicExponent))
            update(codeBytes)
        }
        return digest.digest()
    }


    // ── Message framing ───────────────────────────────────────────────────────
    // The Google TV Polo protocol uses a 1-BYTE length prefix.
    // Verified against all 3 reference implementations:
    //   Go (atvremote):  p.Connection.Write([]byte{byte(len(raw))})
    //   ESP32:           buffer[0] = bufferSize; ssl_send(buffer, bufferSize + 1)
    //   Python:          encode_varint(len) — varint = 1 byte for all msgs < 128 bytes
    //
    // PREVIOUS BUG: used 2-byte big-endian [hi, lo] prefix.
    //   → TV sent [size_byte, proto...]. We computed size = size_byte×256 + proto[0]
    //     (e.g. 1288 bytes). recv() blocked 10 s → SocketTimeoutException.
    //   → pair() = false every time. PIN dialog NEVER appeared.

    private fun send(sock: SSLSocket, payload: ByteArray) {
        sock.outputStream.write(payload.size and 0xFF)  // 1-byte length prefix
        sock.outputStream.write(payload)
        sock.outputStream.flush()
        Log.v(TAG, "TX ${payload.size} bytes: ${payload.take(6).joinToString { "0x%02X".format(it) }}...")
    }

    private fun recv(sock: SSLSocket): ByteArray {
        val size = sock.inputStream.read()  // 1-byte length prefix
            .also { if (it < 0) throw java.io.IOException("connection closed by TV") }
        Log.v(TAG, "RX expecting $size bytes")
        val buf = ByteArray(size)
        var n   = 0
        while (n < size) n += sock.inputStream.read(buf, n, size - n)
            .also { if (it < 0) throw java.io.IOException("connection closed mid-message") }
        return buf
    }

    // ── Pairing messages (port 6467) ──────────────────────────────────────────
    // Built by PairingProto using verified field numbers from polo.proto / pairingmessage.proto.
    // NO hardcoded byte arrays — every field is computed from proto field numbers.

    private fun msgPairRequest() = PairingProto.buildPairingRequest(
        serviceName = SERVICE_NAME,
        clientName  = DEVICE_NAME
    )

    private fun msgOption()  = PairingProto.buildOptions()
    private fun msgConfig()  = PairingProto.buildConfiguration()

    private fun msgSecret(sock: SSLSocket, pin: String): ByteArray {
        val sec = encodeSecret(sock, pin)

        // Client-side PIN checksum: hash[0] must equal int(pin[0:2], 16).
        // Matches the validation in the Python androidtvremote2 reference.
        // If it fails, the user typed a wrong PIN — throw to surface FAILED state.
        val expectedChecksum = pin.substring(0, 2).toInt(16)
        if (sec[0].toInt() and 0xFF != expectedChecksum) {
            Log.w(TAG, "PIN checksum mismatch: hash[0]=0x%02X expected=0x%02X"
                .format(sec[0].toInt() and 0xFF, expectedChecksum))
            throw IllegalArgumentException("Wrong PIN — checksum mismatch (hash[0]≠pin[0:2])")
        }

        return PairingProto.buildSecret(sec)
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

    // RemoteMessage protobuf helpers
    private fun rmVarint(value: Int): ByteArray {
        val out = ArrayList<Byte>(5)
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    private fun rmTag(fieldNumber: Int, wireType: Int): ByteArray =
        rmVarint((fieldNumber shl 3) or wireType)

    private fun rmVarintField(fieldNumber: Int, value: Int): ByteArray =
        rmTag(fieldNumber, 0) + rmVarint(value)

    private fun rmLenField(fieldNumber: Int, data: ByteArray): ByteArray =
        rmTag(fieldNumber, 2) + rmVarint(data.size) + data

    private fun rmStringField(fieldNumber: Int, value: String): ByteArray =
        rmLenField(fieldNumber, value.toByteArray(Charsets.UTF_8))

    private fun msgKey(keyCode: Int): ByteArray {
        // remote_key_inject (field 10)
        // RemoteKeyInject: key_code=field1, direction=field2 (SHORT=3)
        val inner = rmVarintField(1, keyCode) + rmVarintField(2, 3)
        return rmLenField(10, inner)
    }

    private fun msgImeBatchEdit(text: String, counter: Int, fieldCounter: Int): ByteArray {
        // remote_ime_batch_edit (field 21)
        // RemoteImeObject: start/end = text.length-1 (Python androidtvremote2 behavior)
        val cursorPos = (text.length - 1).coerceAtLeast(0)
        val imeObject =
            rmVarintField(1, cursorPos) +
                rmVarintField(2, cursorPos) +
                rmStringField(3, text)

        // RemoteEditInfo: insert=1, text_field_status=imeObject
        val editInfo = rmVarintField(1, 1) + rmLenField(2, imeObject)

        // RemoteImeBatchEdit: ime_counter, field_counter, repeated edit_info
        val batch =
            rmVarintField(1, counter) +
                rmVarintField(2, fieldCounter) +
                rmLenField(3, editInfo)

        return rmLenField(21, batch)
    }

    private fun msgLaunchApp(appLink: String): ByteArray {
        // remote_app_link_launch_request (field 90)
        val launch = rmStringField(1, appLink)
        return rmLenField(90, launch)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80L == 0L) return Pair(result, i - start)
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    private fun updateImeCountersFromBatchEdit(payload: ByteArray) {
        var i = 0
        var nextImeCounter: Int? = null
        var nextFieldCounter: Int? = null

        while (i < payload.size) {
            val (tag, tagLen) = readVarint(payload, i) ?: break
            i += tagLen
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7L).toInt()

            when (wire) {
                0 -> {
                    val (value, len) = readVarint(payload, i) ?: break
                    i += len
                    when (field) {
                        1 -> nextImeCounter = value.toInt()
                        2 -> nextFieldCounter = value.toInt()
                    }
                }
                2 -> {
                    val (len, lenLen) = readVarint(payload, i) ?: break
                    i += lenLen + len.toInt()
                }
                1 -> i += 8
                5 -> i += 4
                else -> break
            }
        }

        if (nextImeCounter != null) imeCounter = nextImeCounter
        if (nextFieldCounter != null) imeFieldCounter = nextFieldCounter
    }

    private fun handleInboundControlMessage(payload: ByteArray): Boolean {
        var i = 0
        var shouldPong = false

        while (i < payload.size) {
            val (tag, tagLen) = readVarint(payload, i) ?: break
            i += tagLen
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7L).toInt()

            when (wire) {
                0 -> {
                    val (_, len) = readVarint(payload, i) ?: break
                    i += len
                }
                2 -> {
                    val (len, lenLen) = readVarint(payload, i) ?: break
                    i += lenLen
                    val dataLen = len.toInt()
                    if (i + dataLen > payload.size) break
                    val fieldBytes = payload.copyOfRange(i, i + dataLen)
                    if (field == 8) {
                        shouldPong = true
                    } else if (field == 21) {
                        updateImeCountersFromBatchEdit(fieldBytes)
                    }
                    i += dataLen
                }
                1 -> i += 8
                5 -> i += 4
                else -> break
            }
        }
        return shouldPong
    }

    // ── Pairing flow ──────────────────────────────────────────────────────────

    /** Called by ViewModel when the user submits the 6-char PIN shown on TV. */
    suspend fun submitPin(pin: String) = pinChannel.send(pin)

    /**
     * Runs the full pairing handshake on port 6467.
     * Suspends at WAITING_FOR_PIN until [submitPin] is called.
     */
    override suspend fun pair(): Boolean = withContext(Dispatchers.IO) {
        onPairingState(PairingState.CONNECTING)
        var sock: SSLSocket? = null
        try {
            sock = openSslSocket(PAIR_PORT)

            send(sock, msgPairRequest());  recv(sock)   // state 0 → 1
            send(sock, msgOption());       recv(sock)   // state 1 → 2
            send(sock, msgConfig());       recv(sock)   // state 2 → 3

            onPairingState(PairingState.WAITING_FOR_PIN)
            val pin = withTimeoutOrNull(120_000L) { pinChannel.receive() }
            if (pin == null) {
                Log.w(TAG, "pair(): PIN entry timed out (120 s)")
                onPairingState(PairingState.FAILED); return@withContext false
            }

            onPairingState(PairingState.VERIFYING_PIN)
            val secretResult = runCatching { msgSecret(sock, pin) }
            if (secretResult.isFailure) {
                Log.e(TAG, "PIN encoding failed: ${secretResult.exceptionOrNull()?.message}")
                onPairingState(PairingState.FAILED); return@withContext false
            }

            send(sock, secretResult.getOrThrow())
            val resp = runCatching { recv(sock) }.getOrElse { byteArrayOf() }

            Log.d(TAG, "Pairing response: ${resp.size} bytes  ${resp.joinToString { "0x%02X".format(it) }}")

            val statusCode = PairingProto.parseStatus(resp)
            val ok = when {
                statusCode == PairingProto.STATUS_OK        -> { Log.i(TAG, "Pairing OK (status=200)"); true }
                statusCode == PairingProto.STATUS_BAD_SECRET-> { Log.e(TAG, "Pairing FAILED: 402 BAD_SECRET"); false }
                statusCode > 0                              -> { Log.w(TAG, "Pairing FAILED: status=$statusCode"); false }
                PairingProto.containsStatusOk(resp)         -> { Log.i(TAG, "Pairing OK (status=200 fallback scan)"); true }
                resp.isEmpty()                              -> { Log.w(TAG, "Pairing FAILED: empty response"); false }
                else                                        -> { Log.w(TAG, "Pairing FAILED: unrecognised response"); false }
            }

            onPairingState(if (ok) PairingState.PAIRED else PairingState.FAILED)
            ok

        } catch (e: Exception) {
            // Log the EXACT exception so user can grep Logcat for "PAIRING"
            Log.e(TAG, "pair() FAILED [${e::class.simpleName}]: ${e.message}", e)
            onPairingState(PairingState.FAILED)
            false
        } finally {
            runCatching { sock?.close() }
        }
    }

    // ── TvController: connect / control ───────────────────────────────────────

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        manualDisconnectRequested = false
        runCatching {
            val sock = openSslSocket(CONTROL_PORT)

            // Run the session handshake synchronously before reporting connected=true.
            // This ensures sendKey() calls are never enqueued before the TV is READY.
            val readyDeferred = CompletableDeferred<Boolean>()
            controlJob = controllerScope.launch {
                runSession(sock, readyDeferred)
            }

            // Wait up to 15 seconds for the handshake to complete
            val ready = withTimeoutOrNull(15_000L) { readyDeferred.await() } ?: false
            if (ready) {
                connected = true
                imeCounter = 0
                imeFieldCounter = 0
                Log.i(TAG, "Control session started: ${device.ip}:$CONTROL_PORT")
            } else {
                controlJob?.cancel()
                runCatching { sock.close() }
                Log.w(TAG, "Control session handshake timed out")
            }
            ready
        }.getOrElse { e -> Log.e(TAG, "connect failed: ${e.message}"); false }
    }

    private suspend fun CoroutineScope.runSession(sock: SSLSocket, readyDeferred: CompletableDeferred<Boolean>) {
        val startedConnected = connected
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
            // Signal connect() that the handshake is complete and we are ready for commands
            readyDeferred.complete(true)

            // State 9: Separation of Read and Write pipelines
            sock.soTimeout = 0 // Blocking read

            val receiverJob = launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val msg = recv(sock)
                        if (msg.isNotEmpty() && handleInboundControlMessage(msg)) {
                            send(sock, msgPong())
                        }
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
                    is CtrlCmd.Payload -> send(sock, cmd.bytes)
                    CtrlCmd.Exit   -> {
                        receiverJob.cancel()
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!readyDeferred.isCompleted) readyDeferred.complete(false)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Session error: ${e.message}")
            if (!readyDeferred.isCompleted) readyDeferred.complete(false)
            val wasConnected = connected || startedConnected
            if (wasConnected && !manualDisconnectRequested) {
                onUnexpectedDisconnect("Google TV control session error: ${e.message ?: "unknown"}")
            }
        }
        finally {
            connected = false
            manualDisconnectRequested = false
            runCatching { sock.close() }
        }
    }

    /** Wait until a message with [expectedTag] arrives; respond to pings. */
    private fun awaitTag(sock: SSLSocket, expectedTag: Int) {
        // Rely on sock.soTimeout (already set) to abort via SocketTimeoutException
        // instead of a hard iteration cap that could terminate prematurely on slow networks.
        while (true) {
            val m = runCatching { recv(sock) }.getOrNull() ?: return
            if (m.isEmpty()) continue
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
        manualDisconnectRequested = true
        cmdChannel.trySend(CtrlCmd.Exit)
        controlJob?.cancel(); controlJob = null
        controllerScope.coroutineContext[Job]?.cancelChildren()
        connected = false
    }

    override fun isConnected() = connected

    override suspend fun sendKey(key: TvKey) {
        val code = KEY_MAP[key] ?: run {
            // Netflix and YouTube are not keycode-mappable; launch them as apps
            when (key) {
                TvKey.NETFLIX  -> { launchApp("com.netflix.ninja");            return }
                TvKey.YOUTUBE  -> { launchApp("com.google.android.youtube.tv"); return }
                else           -> throw UnsupportedOperationException("Google TV does not support key: $key")
            }
        }
        cmdChannel.send(CtrlCmd.Payload(msgKey(code)))
    }

    override suspend fun sendText(text: String) {
        if (text.isBlank()) return

        // Prefer native Google TV IME pipeline when we have the latest IME counters.
        // This avoids firmware/IME keycode mapping bugs where alphabetic input can degrade.
        val imeReady = (imeCounter != 0 || imeFieldCounter != 0)
        if (connected && imeReady) {
            cmdChannel.send(CtrlCmd.Payload(msgImeBatchEdit(text, imeCounter, imeFieldCounter)))
            return
        }

        // Reliable path: ADB text injection when available. Some Google TV builds
        // map remote key-inject letters incorrectly in search IME (e.g. always 'A').
        val injectedByAdb = runCatching {
            val adb = AndroidTvController(device)
            val ok = withTimeoutOrNull(2500L) { adb.connect() } ?: false
            if (!ok) return@runCatching false
            try {
                adb.sendText(text)
                true
            } finally {
                runCatching { adb.disconnect() }
            }
        }.getOrDefault(false)

        if (injectedByAdb) return

        // Last-resort native IME send even without counters.
        // Some devices still accept counter=0 updates.
        if (connected) {
            cmdChannel.send(CtrlCmd.Payload(msgImeBatchEdit(text, imeCounter, imeFieldCounter)))
            return
        }

        // Do not degrade to keycode injection for alphabetic text.
        // On several Google TV firmware/IME combinations this path maps letters
        // incorrectly (commonly all become 'A'). Surface an actionable error instead.
        if (text.any { it.isLetter() }) {
            throw IllegalStateException(
                "Google TV cần ADB được cấp quyền để nhập chữ chính xác. Hãy bật ADB over network và chấp nhận fingerprint trên TV."
            )
        }

        for (ch in text) {
            val code = when {
                ch in '0'..'9' -> 7 + (ch - '0')
                ch in 'a'..'z' -> 29 + (ch - 'a')
                ch in 'A'..'Z' -> {
                    // Send SHIFT + lowercase equivalent for uppercase characters
                    cmdChannel.send(CtrlCmd.Payload(msgKey(59)))  // KEYCODE_SHIFT_LEFT
                    29 + (ch - 'A')
                }
                ch == ' '      -> 62
                ch == '\n'     -> 66
                ch == '\b'     -> 67
                else           -> continue
            }
            cmdChannel.send(CtrlCmd.Payload(msgKey(code)))
            delay(50)
        }
    }

    override suspend fun getInstalledApps(): List<TvApp> = emptyList()

    override suspend fun launchApp(appId: String) {
        if (!connected) {
            throw IllegalStateException("Google TV chưa kết nối")
        }
        // Android TV Remote v2 supports app-link launch.
        // Package IDs are mapped to market://launch?id=<package>.
        val appLink = if (appId.contains("://")) appId else "market://launch?id=$appId"
        cmdChannel.send(CtrlCmd.Payload(msgLaunchApp(appLink)))
    }

    override suspend fun moveMouse(dx: Float, dy: Float) {
        throw UnsupportedOperationException("Google TV Remote Protocol không hỗ trợ chuột. Dùng tab D-Pad.")
    }

    override suspend fun tapMouse() {
        throw UnsupportedOperationException("Google TV Remote Protocol không hỗ trợ chuột. Dùng tab D-Pad.")
    }
}

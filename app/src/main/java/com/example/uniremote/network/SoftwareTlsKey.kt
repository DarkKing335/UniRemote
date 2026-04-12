package com.example.uniremote.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.uniremote.data.SecureCredentialStore
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import javax.security.auth.x500.X500Principal

private const val TAG_SW = "SoftwareTlsKey"

/**
 * Generates and persists a SOFTWARE RSA-2048 key pair + self-signed X.509 certificate.
 *
 * WHY software and not AndroidKeyStore:
 *   Conscrypt's TLS client-auth path internally does:
 *     CryptoUpcalls.rsaSignDigestWithPrivateKey() → Cipher.init(ENCRYPT_MODE, privateKey)
 *   AndroidKeyStore hardware-backed private keys have NO Keymaster mapping for
 *   "private key in ENCRYPT mode". The result is always "Incompatible padding mode"
 *   regardless of how the key was generated (PURPOSE_SIGN / PURPOSE_DECRYPT / any
 *   combination of paddings).
 *
 *   A software JCE key is used directly by Conscrypt's native OpenSSL RSA engine
 *   — the CryptoUpcalls fallback is never triggered.
 *
 * This matches what all reference implementations use:
 *   Python → PEM file on disk
 *   Go     → tls.LoadX509KeyPair() from PEM file
 *   ESP32  → raw key bytes in flash
 *
 * The cert is built with a dependency-free minimal DER encoder (no BouncyCastle).
 * Key material is stored Base64-encoded in app-private SharedPreferences.
 *
 * Call [init] once from Application/ViewModel before the first TLS connection.
 * After that, [getOrCreate] returns the cached pair instantly.
 */
object SoftwareTlsKey {

    @Volatile private var cached: Pair<PrivateKey, X509Certificate>? = null
    @Volatile private var secureStore: SecureCredentialStore? = null

    /** Must be called once (from RemoteViewModel coroutine) with the app context. */
    fun init(ctx: Context) {
        if (cached != null) return
        synchronized(this) {
            if (cached != null) return
            try {
                if (secureStore == null) {
                    secureStore = SecureCredentialStore(ctx.applicationContext)
                }
                cached = loadOrGenerate(ctx.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG_SW, "Key init failed, using in-memory fallback: ${e.message}", e)
                cached = generateInMemory()
            }
        }
    }

    /**
     * Returns the key pair. Safe to call from any thread after [init] has been called.
     * If [init] was never called (edge-case during cold testing), generates an in-memory
     * key with no persistence — the user will need to re-pair after restart.
     */
    fun getOrCreate(): Pair<PrivateKey, X509Certificate> =
        cached ?: synchronized(this) {
            cached ?: generateInMemory().also {
                Log.w(TAG_SW, "getOrCreate() called before init() — using ephemeral key")
                cached = it
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadOrGenerate(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val store = secureStore ?: SecureCredentialStore(ctx.applicationContext).also { secureStore = it }
        val privB64 = store.getTlsPrivateKey()
        val certB64 = store.getTlsCertificate()

        if (privB64 != null && certB64 != null) {
            try {
                val privKey = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                )
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                        ByteArrayInputStream(Base64.decode(certB64, Base64.NO_WRAP))
                    ) as X509Certificate
                Log.i(TAG_SW, "Loaded software RSA key from storage")
                return Pair(privKey, cert)
            } catch (e: Exception) {
                Log.w(TAG_SW, "Stored key invalid, regenerating: ${e.message}")
            }
        }

        Log.i(TAG_SW, "Generating new software RSA-2048 key pair…")
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val cert = buildSelfSignedCert(kp)

        store.putTlsMaterial(
            privateKeyB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP),
            certB64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        )

        Log.i(TAG_SW, "Software RSA-2048 key pair generated and stored")
        return Pair(kp.private, cert)
    }

    /** Generates a transient (non-persisted) key pair — used only as an emergency fallback. */
    private fun generateInMemory(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        return Pair(kp.private, buildSelfSignedCert(kp))
    }

    // ── Minimal X.509v3 DER builder ───────────────────────────────────────────
    //
    // Builds a minimal self-signed X.509v3 certificate using raw ASN.1/DER encoding.
    // No external dependencies (no BouncyCastle, no sun.security.*).
    //
    // Structure matches RFC 5280:
    //   Certificate ::= SEQUENCE {
    //     tbsCertificate   TBSCertificate,
    //     signatureAlgorithm AlgorithmIdentifier,
    //     signature        BIT STRING }
    //
    //   TBSCertificate ::= SEQUENCE {
    //     version          [0] EXPLICIT INTEGER DEFAULT 0,
    //     serialNumber     INTEGER,
    //     signature        AlgorithmIdentifier,
    //     issuer           Name,
    //     validity         Validity,
    //     subject          Name,
    //     subjectPublicKeyInfo SubjectPublicKeyInfo }
    //
    // Note: java.security.PublicKey.getEncoded()     → DER SubjectPublicKeyInfo  ✓
    //       javax.security.auth.x500.X500Principal.getEncoded() → DER X.500 Name ✓
    // We only need to build the wrapping structure manually.

    private fun buildSelfSignedCert(kp: KeyPair): X509Certificate {
        val subject = X500Principal("CN=atvremote, O=Google Inc., C=US")
        val now   = Date()
        val later = Date(now.time + 10L * 365 * 24 * 3600 * 1000)

        // sha256WithRSAEncryption (1.2.840.113549.1.1.11)
        val sha256RsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x0B
        )
        val algId = derSeq(sha256RsaOid, derNull())

        val tbs = derSeq(
            derCtx0(derInt(BigInteger.valueOf(2L))), // [0] version = 2 → X.509v3
            derInt(BigInteger.ONE),              // serialNumber = 1
            algId,                               // signatureAlgorithm
            subject.encoded,                     // issuer  (already DER X.500Name)
            derSeq(                              // validity
                derUtcTime(now),
                derUtcTime(later)
            ),
            subject.encoded,                     // subject (self-signed → same as issuer)
            kp.public.encoded                    // subjectPublicKeyInfo (already DER)
        )

        // Sign the TBS with our private key
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(tbs)
        }
        val signature = sig.sign()

        val certDer = derSeq(
            tbs,
            algId,
            derBitStr(signature)     // BIT STRING  (0 unused bits prefix)
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    // ── DER primitives ────────────────────────────────────────────────────────

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val len = value.size
        val lenBytes = when {
            len < 128   -> byteArrayOf(len.toByte())
            len < 256   -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), (len and 0xFF).toByte())
            else        -> throw IllegalArgumentException("DER value too large: $len bytes")
        }
        return byteArrayOf(tag.toByte()) + lenBytes + value
    }

    private fun derSeq(vararg items: ByteArray): ByteArray =
        tlv(0x30, items.fold(byteArrayOf(), ByteArray::plus))

    private fun derInt(n: BigInteger): ByteArray = tlv(0x02, n.toByteArray())

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    /** Context [0] EXPLICIT wrapper — used for the X.509v3 version field */
    private fun derCtx0(inner: ByteArray): ByteArray = tlv(0xA0, inner)

    /** BIT STRING with 0 unused bits prefix */
    private fun derBitStr(data: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + data)

    private fun derUtcTime(date: Date): ByteArray {
        // UTCTime format: YYMMDDHHMMSSZ (13 chars), e.g. "250412174230Z"
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).also {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }
        return tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }
}

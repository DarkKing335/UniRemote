package com.example.uniremote.network

import android.util.Log

/**
 * Zero-dependency Protobuf encoder/decoder for the Google TV Polo pairing protocol.
 *
 * Field numbers verified against ALL 3 reference implementations:
 *   - polo.proto         (Python: tronikos/androidtvremote2)
 *   - pairingmessage.proto (Go:   drosoCode/atvremote)
 *   - PairingMessageManager.cpp (C++: nguyluky/ESP32_android_tv_remote_v2)
 *
 * OuterMessage / PairingMessage field layout:
 *   Field 1  = protocol_version  (varint, value = 2)
 *   Field 2  = status            (varint, STATUS_OK = 200, STATUS_BAD_SECRET = 402)
 *   Field 10 = pairing_request   (LEN)
 *   Field 11 = pairing_request_ack (LEN)
 *   Field 20 = options           (LEN)
 *   Field 30 = configuration     (LEN)
 *   Field 31 = configuration_ack (LEN)
 *   Field 40 = pairing_secret    (LEN)   ← was incorrectly field 24 (0xC2,0x01)
 *   Field 41 = pairing_secret_ack (LEN)
 */
object PairingProto {

    private const val TAG = "PairingProto"

    // ── OuterMessage field numbers ────────────────────────────────────────────
    private const val F_VERSION         = 1
    private const val F_STATUS          = 2
    private const val F_PAIR_REQ        = 10
    private const val F_OPTIONS         = 20
    private const val F_CONFIG          = 30
    private const val F_SECRET          = 40   // ← was 24 in old broken code!

    // ── Status codes (OuterMessage.Status enum) ───────────────────────────────
    const val STATUS_OK                = 200
    const val STATUS_ERROR             = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET        = 402

    // ── Encoding helpers ──────────────────────────────────────────────────────

    /** Encode an unsigned int as a protobuf varint. */
    private fun varint(value: Int): ByteArray {
        val buf = mutableListOf<Byte>()
        var v = value
        while (v and 0x7F.inv() != 0) {
            buf.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        buf.add((v and 0x7F).toByte())
        return buf.toByteArray()
    }

    /** Compute the field tag byte(s) for a given field number and wire type. */
    private fun tag(fieldNumber: Int, wireType: Int): ByteArray =
        varint((fieldNumber shl 3) or wireType)

    /** Encode a varint field (wire type 0). */
    private fun varintField(fieldNumber: Int, value: Int): ByteArray =
        tag(fieldNumber, 0) + varint(value)

    /** Encode a length-delimited field (wire type 2). */
    private fun lenField(fieldNumber: Int, data: ByteArray): ByteArray =
        tag(fieldNumber, 2) + varint(data.size) + data

    /** Encode a UTF-8 string field (wire type 2). */
    private fun stringField(fieldNumber: Int, str: String): ByteArray =
        lenField(fieldNumber, str.toByteArray(Charsets.UTF_8))

    /**
     * Standard header for every outgoing PairingMessage:
     *   protocol_version = 2  (field 1)
     *   status = STATUS_OK    (field 2, varint 200 = 0xC8 0x01)
     */
    private fun header(): ByteArray =
        varintField(F_VERSION, 2) + varintField(F_STATUS, STATUS_OK)

    // ── Message builders ──────────────────────────────────────────────────────

    /**
     * PairingMessage { pairing_request: PairingRequest { service_name, client_name } }
     *
     * Python ref: msg.pairing_request.service_name = "atvremote"
     * Go ref:     PairingRequest{ ServiceName: "com.droso.test", ClientName: "test" }
     */
    fun buildPairingRequest(
        serviceName: String = "atvremote",
        clientName: String  = "UniRemote"
    ): ByteArray {
        // PairingRequest: service_name=field1, client_name=field2
        val inner = stringField(1, serviceName) + stringField(2, clientName)
        return header() + lenField(F_PAIR_REQ, inner)
    }

    /**
     * PairingMessage { options: Options { input_encodings: [HEXADECIMAL/6], preferred_role: INPUT } }
     *
     * Python ref:
     *   enc.type = Options.Encoding.ENCODING_TYPE_HEXADECIMAL  (= 3)
     *   enc.symbol_length = 6
     *   new_msg.options.preferred_role = Options.RoleType.ROLE_TYPE_INPUT (= 1)
     */
    fun buildOptions(): ByteArray {
        // Encoding sub-message: type=HEXADECIMAL(3) field1, symbol_length=6 field2
        val encoding = varintField(1, 3) + varintField(2, 6)
        // Options: input_encodings=field1 (repeated), preferred_role=field3
        val inner    = lenField(1, encoding) + varintField(3, 1)
        return header() + lenField(F_OPTIONS, inner)
    }

    /**
     * PairingMessage { configuration: Configuration { encoding: {HEXADECIMAL,6}, client_role: INPUT } }
     *
     * Python ref:
     *   new_msg.configuration.client_role = Options.RoleType.ROLE_TYPE_INPUT
     *   new_msg.configuration.encoding.type = ENCODING_TYPE_HEXADECIMAL
     *   new_msg.configuration.encoding.symbol_length = 6
     */
    fun buildConfiguration(): ByteArray {
        // Encoding sub-message
        val encoding = varintField(1, 3) + varintField(2, 6)
        // Configuration: encoding=field1, client_role=field2
        val inner    = lenField(1, encoding) + varintField(2, 1)
        return header() + lenField(F_CONFIG, inner)
    }

    /**
     * PairingMessage { pairing_secret: PairingSecret { secret: <32-byte-hash> } }
     *
     * Field 40 LEN:  (40 << 3) | 2 = 322  → varint = [0xC2, 0x02]
     * PairingSecret.secret is field 1 (LEN).
     *
     * ⚠ Previous bug in GoogleTvController: used field 24 tag [0xC2, 0x01].
     *   TV received OuterMessage with NO pairing_secret → STATUS_BAD_SECRET.
     */
    fun buildSecret(secretBytes: ByteArray): ByteArray {
        // PairingSecret: secret bytes (field 1, LEN)
        val inner = lenField(1, secretBytes)
        return header() + lenField(F_SECRET, inner)
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Parse the OuterMessage response from the TV and return the status code.
     *
     * Walks the protobuf varint field list properly — no fixed byte indices.
     * Returns [STATUS_OK] (200) on success, another status code on error,
     * or -1 if the response could not be parsed.
     *
     * Fallback to [containsStatusOk] is performed by the caller.
     */
    fun parseStatus(data: ByteArray): Int {
        Log.d(TAG, "Parsing ${data.size} bytes: ${data.joinToString { "0x%02X".format(it) }}")
        var i = 0
        while (i < data.size) {
            val (tagValue, tagLen) = readVarint(data, i) ?: run {
                Log.w(TAG, "parseStatus: could not read tag at offset $i")
                return -1
            }
            i += tagLen
            val fieldNumber = (tagValue shr 3).toInt()
            val wireType    = (tagValue and 0x7L).toInt()

            when (wireType) {
                0 -> { // varint field
                    val (fValue, fLen) = readVarint(data, i) ?: run {
                        Log.w(TAG, "parseStatus: could not read varint at offset $i")
                        return -1
                    }
                    i += fLen
                    if (fieldNumber == F_STATUS) {
                        val code = fValue.toInt()
                        Log.d(TAG, "parseStatus: found status=$code")
                        return code
                    }
                }
                2 -> { // length-delimited — skip
                    val (len, lenLen) = readVarint(data, i) ?: run {
                        Log.w(TAG, "parseStatus: could not read LEN at offset $i")
                        return -1
                    }
                    i += lenLen + len.toInt()
                }
                else -> {
                    Log.w(TAG, "parseStatus: unsupported wire type $wireType at offset $i — stopping")
                    return -1
                }
            }
        }
        Log.w(TAG, "parseStatus: field $F_STATUS (status) not found in response")
        return -1
    }

    /**
     * Fast-path scan: searches for the raw byte pattern that encodes
     * field 2 (status) = varint 200 (STATUS_OK) ANYWHERE in the byte array.
     *
     * Pattern: [0x10, 0xC8, 0x01]
     *   0x10 = (2 << 3) | 0 = field-2 varint tag
     *   0xC8 = low 7 bits of 200 with MSB set  (200 & 0x7F | 0x80)
     *   0x01 = high bits of 200                (200 >> 7)
     *
     * Used when parseStatus() returns -1 (unexpected field order, partial data, etc.).
     */
    fun containsStatusOk(data: ByteArray): Boolean {
        for (i in 0..data.size - 3) {
            if (data[i]   == 0x10.toByte() &&
                data[i+1] == 0xC8.toByte() &&
                data[i+2] == 0x01.toByte()) {
                Log.d(TAG, "containsStatusOk: pattern found at offset $i")
                return true
            }
        }
        return false
    }

    /**
     * Read a protobuf-encoded varint starting at [start] in [data].
     * Returns Pair(value, bytesConsumed) or null on failure.
     */
    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift  = 0
        var i      = start
        while (i < data.size) {
            val b = data[i++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80L == 0L) return Pair(result, i - start)
            shift += 7
            if (shift >= 64) return null  // malformed: varint too long
        }
        return null  // buffer ended mid-varint
    }
}

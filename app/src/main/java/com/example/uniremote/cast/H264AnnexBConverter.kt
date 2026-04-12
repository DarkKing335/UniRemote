package com.example.uniremote.cast

/**
 * Converts MediaCodec H.264 output between AVCC and Annex-B representations.
 *
 * - Many encoders output AVCC (4-byte NAL length prefix).
 * - Network players commonly expect Annex-B (start codes 0x00000001).
 */
object H264AnnexBConverter {

    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun toAnnexB(sample: ByteArray): ByteArray {
        if (sample.isEmpty()) return sample
        if (startsWithStartCode(sample)) return sample
        return avccToAnnexB(sample)
    }

    fun buildCodecConfig(csd0: ByteArray?, csd1: ByteArray?): ByteArray? {
        val p0 = csd0?.takeIf { it.isNotEmpty() }?.let(::normalizeNalu)
        val p1 = csd1?.takeIf { it.isNotEmpty() }?.let(::normalizeNalu)
        return when {
            p0 == null && p1 == null -> null
            p0 != null && p1 != null -> p0 + p1
            p0 != null -> p0
            else -> p1
        }
    }

    private fun normalizeNalu(nalu: ByteArray): ByteArray {
        return if (startsWithStartCode(nalu)) nalu else START_CODE + nalu
    }

    private fun startsWithStartCode(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte()) {
            if (bytes[2] == 0x01.toByte()) return true
            if (bytes[2] == 0x00.toByte() && bytes[3] == 0x01.toByte()) return true
        }
        return false
    }

    private fun avccToAnnexB(sample: ByteArray): ByteArray {
        var offset = 0
        val out = ArrayList<Byte>(sample.size + 32)

        while (offset + 4 <= sample.size) {
            val nalLength =
                ((sample[offset].toInt() and 0xFF) shl 24) or
                ((sample[offset + 1].toInt() and 0xFF) shl 16) or
                ((sample[offset + 2].toInt() and 0xFF) shl 8) or
                (sample[offset + 3].toInt() and 0xFF)
            offset += 4

            if (nalLength <= 0 || offset + nalLength > sample.size) {
                // Fallback: treat the whole payload as one NAL unit.
                return START_CODE + sample
            }

            START_CODE.forEach { out.add(it) }
            for (i in 0 until nalLength) {
                out.add(sample[offset + i])
            }
            offset += nalLength
        }

        if (out.isEmpty()) {
            return START_CODE + sample
        }
        return out.toByteArray()
    }
}

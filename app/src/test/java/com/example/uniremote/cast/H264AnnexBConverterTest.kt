package com.example.uniremote.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class H264AnnexBConverterTest {

    @Test
    fun `toAnnexB keeps annexb input unchanged`() {
        val annexB = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x67, 0x64.toByte(), 0x00, 0x1F
        )

        val out = H264AnnexBConverter.toAnnexB(annexB)
        assertArrayEquals(annexB, out)
    }

    @Test
    fun `toAnnexB converts avcc payload with 2 nals`() {
        val avcc = byteArrayOf(
            0x00, 0x00, 0x00, 0x02, 0x67, 0x42,
            0x00, 0x00, 0x00, 0x03, 0x68, 0x11, 0x22
        )

        val expected = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42,
            0x00, 0x00, 0x00, 0x01, 0x68, 0x11, 0x22
        )

        val out = H264AnnexBConverter.toAnnexB(avcc)
        assertArrayEquals(expected, out)
    }

    @Test
    fun `buildCodecConfig prepends start code when needed`() {
        val csd0 = byteArrayOf(0x67, 0x64.toByte(), 0x00, 0x28)
        val csd1 = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

        val config = H264AnnexBConverter.buildCodecConfig(csd0, csd1)
        assertNotNull(config)

        val expected = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x64.toByte(), 0x00, 0x28,
            0x00, 0x00, 0x00, 0x01, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()
        )
        assertArrayEquals(expected, config)
    }
}

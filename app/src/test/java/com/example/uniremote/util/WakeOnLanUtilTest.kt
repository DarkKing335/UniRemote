package com.example.uniremote.util

import org.junit.Assert.*
import org.junit.Test

class WakeOnLanUtilTest {

    @Test
    fun `magic packet has correct length 102 bytes`() {
        val packet = buildMagicPacketReflected("AA:BB:CC:DD:EE:FF")
        assertEquals(102, packet.size)
    }

    @Test
    fun `magic packet starts with 6 xFF bytes`() {
        val packet = buildMagicPacketReflected("AA:BB:CC:DD:EE:FF")
        val header = packet.take(6)
        assertTrue("Header must be all 0xFF", header.all { it == 0xFF.toByte() })
    }

    @Test
    fun `magic packet contains MAC repeated 16 times`() {
        val mac = "AA:BB:CC:DD:EE:FF"
        val packet = buildMagicPacketReflected(mac)
        val macBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        for (i in 0 until 16) {
            val offset = 6 + i * 6
            val chunk = packet.sliceArray(offset until offset + 6)
            assertArrayEquals("MAC at repetition $i is wrong", macBytes, chunk)
        }
    }

    @Test
    fun `parseMac accepts colon-separated format`() {
        // Test via magic packet structure — no exception means parsing succeeded
        assertDoesNotThrow { buildMagicPacketReflected("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    fun `parseMac accepts hyphen-separated format`() {
        assertDoesNotThrow { buildMagicPacketReflected("AA-BB-CC-DD-EE-FF") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseMac rejects invalid MAC`() {
        buildMagicPacketReflected("INVALID")
    }

    // Access private buildMagicPacket via reflection for testing
    private fun buildMagicPacketReflected(mac: String): ByteArray {
        val util = WakeOnLanUtil
        val parseMac = util.javaClass.getDeclaredMethod("parseMac", String::class.java)
            .also { it.isAccessible = true }
        val macBytes = parseMac.invoke(util, mac) as ByteArray
        val buildPacket = util.javaClass.getDeclaredMethod("buildMagicPacket", ByteArray::class.java)
            .also { it.isAccessible = true }
        return buildPacket.invoke(util, macBytes) as ByteArray
    }

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e.message}")
        }
    }
}

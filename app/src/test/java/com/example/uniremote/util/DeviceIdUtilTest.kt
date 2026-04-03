package com.example.uniremote.util

import org.junit.Assert.*
import org.junit.Test

class DeviceIdUtilTest {

    @Test
    fun `stableId uses sanitized MAC when MAC is valid`() {
        val id = DeviceIdUtil.stableId(mac = "AA:BB:CC:DD:EE:FF", ip = "192.168.1.1", name = "TV")
        assertEquals("AABBCCDDEEFF", id)
    }

    @Test
    fun `stableId uses sanitized MAC with hyphen separator`() {
        val id = DeviceIdUtil.stableId(mac = "AA-BB-CC-DD-EE-FF", ip = "192.168.1.1", name = "TV")
        assertEquals("AABBCCDDEEFF", id)
    }

    @Test
    fun `stableId falls back to SHA256 when MAC is empty`() {
        val id = DeviceIdUtil.stableId(mac = "", ip = "192.168.1.1", name = "TV")
        // SHA-256 based ID is 16 lowercase hex chars
        assertTrue("Expected 16-char hex id, got: $id", id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `stableId falls back to SHA256 when MAC is all zeros`() {
        val id = DeviceIdUtil.stableId(mac = "00:00:00:00:00:00", ip = "192.168.1.1", name = "TV")
        assertTrue("Expected 16-char hex id, got: $id", id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `stableId SHA256 is deterministic for same input`() {
        val id1 = DeviceIdUtil.stableId(mac = "", ip = "192.168.1.100", name = "Samsung TV")
        val id2 = DeviceIdUtil.stableId(mac = "", ip = "192.168.1.100", name = "Samsung TV")
        assertEquals(id1, id2)
    }

    @Test
    fun `stableId SHA256 differs for different inputs`() {
        val id1 = DeviceIdUtil.stableId(mac = "", ip = "192.168.1.1", name = "TV A")
        val id2 = DeviceIdUtil.stableId(mac = "", ip = "192.168.1.2", name = "TV B")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stableId never returns negative string`() {
        // Old hashCode() implementation could produce negative IDs; SHA-256 never can
        repeat(50) { i ->
            val id = DeviceIdUtil.stableId(mac = "", ip = "10.0.0.$i", name = "Device $i")
            assertFalse("ID should not start with '-', got: $id", id.startsWith("-"))
        }
    }
}

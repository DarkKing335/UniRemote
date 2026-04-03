package com.example.uniremote.data

import com.example.uniremote.network.TvKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the JSON serialization/deserialization of KnownDevice and UserMacro
 * used by AppPreferences — without requiring Android instrumentation.
 */
class SerializationTest {

    private val gson = Gson()

    // ── KnownDevice JSON round-trip ───────────────────────────────────────────

    @Test
    fun `KnownDevice serializes and deserializes correctly`() {
        val device = KnownDevice(
            id              = "AABBCCDDEEFF",
            name            = "Living Room TV",
            brand           = "SAMSUNG",
            ip              = "192.168.1.100",
            mac             = "AA:BB:CC:DD:EE:FF",
            port            = 8001,
            ssid            = "MyHomeWifi",
            lastConnectedMs = 1_700_000_000_000L,
            lastSeenMs      = 1_700_000_000_500L,
            isOnline        = true,
            token           = "abc123token"
        )

        val json = gson.toJson(listOf(device))
        val type = object : TypeToken<List<KnownDevice>>() {}.type
        val result: List<KnownDevice> = gson.fromJson(json, type)

        assertEquals(1, result.size)
        val restored = result[0]
        assertEquals(device.id, restored.id)
        assertEquals(device.name, restored.name)
        assertEquals(device.brand, restored.brand)
        assertEquals(device.ip, restored.ip)
        assertEquals(device.mac, restored.mac)
        assertEquals(device.port, restored.port)
        assertEquals(device.ssid, restored.ssid)
        assertEquals(device.lastConnectedMs, restored.lastConnectedMs)
        assertEquals(device.isOnline, restored.isOnline)
        assertEquals(device.token, restored.token)
    }

    @Test
    fun `KnownDevice with null token round-trips correctly`() {
        val device = KnownDevice(
            id = "test", name = "TV", brand = "LG",
            ip = "10.0.0.1", mac = "", port = 3000,
            ssid = "wifi", lastConnectedMs = 0L, token = null
        )
        val json = gson.toJson(listOf(device))
        val type = object : TypeToken<List<KnownDevice>>() {}.type
        val result: List<KnownDevice> = gson.fromJson(json, type)
        assertNull(result[0].token)
    }

    @Test
    fun `empty device list serializes to JSON array`() {
        val json = gson.toJson(emptyList<KnownDevice>())
        assertTrue(json.trim().startsWith("["))
    }

    @Test
    fun `malformed JSON returns empty list gracefully`() {
        val raw = "not_valid_json{{{"
        // Simulate AppPreferences.deserializeKnownDevices() behavior
        val result = try {
            val type = object : TypeToken<List<KnownDevice>>() {}.type
            gson.fromJson<List<KnownDevice>>(raw, type)
        } catch (e: Exception) {
            emptyList<KnownDevice>()
        }
        assertTrue(result.isEmpty())
    }

    // ── UserMacro JSON round-trip ────────────────────────────────────────────

    @Test
    fun `UserMacro serializes and deserializes with TvKey list`() {
        val macro = UserMacro(
            id = "macro_001",
            name = "Movie Night",
            description = "Home → Netflix",
            icon = "nightlight",
            keys = listOf(TvKey.HOME, TvKey.NETFLIX, TvKey.OK)
        )

        val json = gson.toJson(listOf(macro))
        val type = object : TypeToken<List<UserMacro>>() {}.type
        val result: List<UserMacro> = gson.fromJson(json, type)

        assertEquals(1, result.size)
        assertEquals(macro.id, result[0].id)
        assertEquals(macro.name, result[0].name)
        assertEquals(macro.keys, result[0].keys)
    }

    @Test
    fun `UserMacro with all TvKeys round-trips without loss`() {
        val allKeys = TvKey.values().toList()
        val macro = UserMacro(id = "all_keys", name = "All", description = "", icon = "", keys = allKeys)
        val json = gson.toJson(listOf(macro))
        val type = object : TypeToken<List<UserMacro>>() {}.type
        val result: List<UserMacro> = gson.fromJson(json, type)
        assertEquals(allKeys, result[0].keys)
    }

    // ── KnownDevice.toDomain() ───────────────────────────────────────────────

    @Test
    fun `toDomain maps known TvBrand correctly`() {
        val device = KnownDevice(
            id = "id", name = "TV", brand = "SAMSUNG",
            ip = "192.168.1.1", mac = "", port = 8001,
            ssid = "", lastConnectedMs = 0L
        )
        val domain = device.toDomain()
        assertEquals(TvBrand.SAMSUNG, domain.brand)
    }

    @Test
    fun `toDomain defaults to UNKNOWN for unrecognized brand string`() {
        val device = KnownDevice(
            id = "id", name = "TV", brand = "TOTALLY_UNKNOWN_BRAND",
            ip = "192.168.1.1", mac = "", port = 8001,
            ssid = "", lastConnectedMs = 0L
        )
        val domain = device.toDomain()
        assertEquals(TvBrand.UNKNOWN, domain.brand)
    }
}

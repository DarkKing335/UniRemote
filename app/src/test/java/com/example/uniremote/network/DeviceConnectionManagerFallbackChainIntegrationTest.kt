package com.example.uniremote.network

import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConnectionManagerFallbackChainIntegrationTest {

    @Test
    fun `panasonic fallback chain excludes stub controller`() {
        val manager = DeviceConnectionManager()
        val device = TvDevice(
            id = "pana-1",
            name = "Panasonic Living Room",
            brand = TvBrand.PANASONIC,
            ip = "192.168.1.55"
        )

        val enabled = enabledControllerNames(manager, device)
        assertEquals(listOf("AndroidTvController"), enabled)
    }

    @Test
    fun `hisense fallback chain skips stub and keeps google plus adb`() {
        val manager = DeviceConnectionManager()
        val device = TvDevice(
            id = "hisense-1",
            name = "Hisense TV",
            brand = TvBrand.HISENSE,
            ip = "192.168.1.60"
        )

        val enabled = enabledControllerNames(manager, device)
        assertEquals(listOf("GoogleTvController", "AndroidTvController"), enabled)
    }

    @Test
    fun `unknown chain keeps multi-controller fallback order`() {
        val manager = DeviceConnectionManager()
        val device = TvDevice(
            id = "unknown-1",
            name = "Unknown TV",
            brand = TvBrand.UNKNOWN,
            ip = "192.168.1.70"
        )

        val enabled = enabledControllerNames(manager, device)
        assertEquals(4, enabled.size)
        assertEquals("SamsungTvController", enabled[0])
        assertEquals("LgWebOsController", enabled[1])
        assertEquals("GoogleTvController", enabled[2])
        assertEquals("AndroidTvController", enabled[3])
        assertTrue(enabled.contains("AndroidTvController"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun enabledControllerNames(manager: DeviceConnectionManager, device: TvDevice): List<String> {
        val method = DeviceConnectionManager::class.java
            .getDeclaredMethod("controllerCandidates", TvDevice::class.java)
            .apply { isAccessible = true }

        val candidates = method.invoke(manager, device) as List<Any>

        return candidates
            .mapNotNull { candidate ->
                val candidateClass = candidate.javaClass
                val name = candidateClass.getDeclaredField("name").apply { isAccessible = true }
                    .get(candidate) as String
                val priority = candidateClass.getDeclaredField("priority").apply { isAccessible = true }
                    .getInt(candidate)
                val supports = candidateClass.getDeclaredField("supports").apply { isAccessible = true }
                    .get(candidate) as (TvDevice) -> Boolean

                if (supports(device)) Pair(priority, name) else null
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }
}

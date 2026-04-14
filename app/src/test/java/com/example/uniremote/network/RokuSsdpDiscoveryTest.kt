package com.example.uniremote.network

import com.example.uniremote.data.TvBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuSsdpDiscoveryTest {

    @Test
    fun `manual Roku device accepts LAN IPv4 and sets Roku defaults`() {
        val device = RokuSsdpDiscovery.buildManualRokuDevice("192.168.1.45", "Living Room Roku")
        assertNotNull(device)
        assertEquals(TvBrand.ROKU, device!!.brand)
        assertEquals("192.168.1.45", device.ip)
        assertEquals(TvBrand.ROKU.defaultPort, device.port)
        assertEquals("Living Room Roku", device.name)
    }

    @Test
    fun `manual Roku device rejects public IPv4`() {
        val device = RokuSsdpDiscovery.buildManualRokuDevice("8.8.8.8")
        assertNull(device)
    }

    @Test
    fun `isLanIpv4 allows private ranges and blocks public IP`() {
        assertTrue(RokuSsdpDiscovery.isLanIpv4("192.168.0.10"))
        assertTrue(RokuSsdpDiscovery.isLanIpv4("10.0.0.5"))
        assertTrue(RokuSsdpDiscovery.isLanIpv4("172.16.3.7"))
        assertTrue(RokuSsdpDiscovery.isLanIpv4("172.31.255.1"))

        assertFalse(RokuSsdpDiscovery.isLanIpv4("172.32.0.1"))
        assertFalse(RokuSsdpDiscovery.isLanIpv4("1.1.1.1"))
        assertFalse(RokuSsdpDiscovery.isLanIpv4("256.1.1.1"))
        assertFalse(RokuSsdpDiscovery.isLanIpv4("not-an-ip"))
    }

    @Test
    fun `selectLanIp prefers parsed LAN then sender LAN`() {
        val parsedPreferred = RokuSsdpDiscovery.selectLanIp("192.168.1.100", "10.0.0.4")
        assertEquals("192.168.1.100", parsedPreferred)

        val senderFallback = RokuSsdpDiscovery.selectLanIp("44.44.44.44", "192.168.1.9")
        assertEquals("192.168.1.9", senderFallback)

        val rejectOutsideLan = RokuSsdpDiscovery.selectLanIp("8.8.8.8", "52.1.2.3")
        assertNull(rejectOutsideLan)
    }

    @Test
    fun `parseSsdpHeaders extracts location and server case-insensitively`() {
        val payload = """
            HTTP/1.1 200 OK

            LOCATION: http://192.168.1.20:8060/

            ST: roku:ecp

            SERVER: Roku/14.0 UPnP/1.0

            CACHE-CONTROL: max-age=3600

            

        """.trimIndent()

        val headers = RokuSsdpDiscovery.parseSsdpHeadersForTest(payload)
        assertEquals("http://192.168.1.20:8060/", headers["location"])
        assertEquals("roku:ecp", headers["st"])
        assertEquals("Roku/14.0 UPnP/1.0", headers["server"])
    }

    @Test
    fun `parseRokuDescription parses friendly name and normalizes MAC`() {
        val xml = """
            <root>
              <device>
                <friendlyName>Bedroom Roku TV</friendlyName>
                <deviceType>urn:roku-com:device:player:1-0</deviceType>
                <modelName>Roku Streambar</modelName>
                <wifi-mac>aa-bb-cc-dd-ee-ff</wifi-mac>
              </device>
            </root>
        """.trimIndent()

        val parsed = RokuSsdpDiscovery.parseRokuDescriptionForTest(xml)
        assertEquals("Bedroom Roku TV", parsed.friendlyName)
        assertEquals("urn:roku-com:device:player:1-0", parsed.deviceType)
        assertEquals("Roku Streambar", parsed.modelName)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.macAddress)
    }

    @Test
    fun `parseRokuDescription falls back to model name when friendly name is empty`() {
        val xml = """
            <root>
              <device>
                <modelName>Roku Ultra</modelName>
              </device>
            </root>
        """.trimIndent()

        val parsed = RokuSsdpDiscovery.parseRokuDescriptionForTest(xml)
        assertEquals("Roku Ultra", parsed.friendlyName)
    }
}

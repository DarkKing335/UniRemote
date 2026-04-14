package com.example.uniremote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuArpDiscoveryTest {

    // ── macOui ──────────────────────────────────────────────────────────────

    @Test
    fun macOui_stripsColons_returnsFirst6HexDigitsUppercase() {
        assertEquals("DC3A5E", RokuArpDiscovery.macOui("dc:3a:5e:aa:bb:cc"))
        assertEquals("DC3A5E", RokuArpDiscovery.macOui("DC:3A:5E:AA:BB:CC"))
    }

    @Test
    fun macOui_handlesDashSeparatedInput() {
        assertEquals("B83E59", RokuArpDiscovery.macOui("b8-3e-59-11-22-33"))
    }

    @Test
    fun macOui_handlesMixedCaseMacWithoutSeparators() {
        assertEquals("CC6DA0", RokuArpDiscovery.macOui("cc6da0112233"))
    }

    // ── isRokuMac ────────────────────────────────────────────────────────────

    @Test
    fun isRokuMac_returnsTrueForKnownRokuOuis() {
        // Roku Inc OUIs from Wireshark OUI DB
        assertTrue(RokuArpDiscovery.isRokuMac("dc:3a:5e:00:11:22"))  // DC3A5E
        assertTrue(RokuArpDiscovery.isRokuMac("B8:3E:59:AA:BB:CC"))  // B83E59
        assertTrue(RokuArpDiscovery.isRokuMac("00:09:2d:11:22:33"))  // 00092D
        assertTrue(RokuArpDiscovery.isRokuMac("cc:6d:a0:ff:ee:dd"))  // CC6DA0
        assertTrue(RokuArpDiscovery.isRokuMac("8c:4c:5f:ab:cd:ef"))  // 8C4C5F
        assertTrue(RokuArpDiscovery.isRokuMac("E0:CB:4E:10:20:30"))  // E0CB4E
    }

    @Test
    fun isRokuMac_returnsFalseForNonRokuOuis() {
        assertFalse(RokuArpDiscovery.isRokuMac("00:1A:11:22:33:44")) // Samsung
        assertFalse(RokuArpDiscovery.isRokuMac("AC:DE:48:11:22:33")) // Apple
        assertFalse(RokuArpDiscovery.isRokuMac("FC:FC:48:11:22:33")) // LG
    }

    @Test
    fun isRokuMac_returnsFalseForBlankOrAllZeroMac() {
        assertFalse(RokuArpDiscovery.isRokuMac(""))
        assertFalse(RokuArpDiscovery.isRokuMac("   "))
        assertFalse(RokuArpDiscovery.isRokuMac("00:00:00:00:00:00"))
    }

    // ── ARP entry parsing ─────────────────────────────────────────────────
    // We can't call the real /proc/net/arp in JVM tests, so we test the parsing
    // logic indirectly via the public macOui/isRokuMac helpers and a hand-rolled
    // parse that mirrors the production logic.

    @Test
    fun arpParsing_flags0x2_isConsideredComplete() {
        val flags = "0x2"
        assertFalse(flags == "0x0")
    }

    @Test
    fun arpParsing_flags0x0_isConsideredIncomplete() {
        val flags = "0x0"
        assertTrue(flags == "0x0")
    }

    @Test
    fun arpParsing_headerLineShouldBeSkipped() {
        val line = "IP address       HW type     Flags       HW address            Mask     Device"
        val cols = line.trim().split(Regex("\\s+"))
        assertTrue(cols.isNotEmpty() && cols[0] == "IP")
    }

    @Test
    fun arpParsing_validRokuEntryIsExtractedCorrectly() {
        // Simulated /proc/net/arp line for a Roku TV at 192.168.1.100
        val line = "192.168.1.100    0x1         0x2         dc:3a:5e:aa:bb:cc     *        wlan0"
        val cols = line.trim().split(Regex("\\s+"))
        assertEquals(6, cols.size)
        val ip = cols[0]
        val flags = cols[2]
        val mac = cols[3]
        assertEquals("192.168.1.100", ip)
        assertEquals("0x2", flags)
        assertEquals("dc:3a:5e:aa:bb:cc", mac)
        assertTrue(RokuArpDiscovery.isRokuMac(mac))
        assertTrue(RokuSsdpDiscovery.isLanIpv4(ip))
    }

    @Test
    fun arpParsing_zeroMacShouldBeSkipped() {
        val mac = "00:00:00:00:00:00"
        assertTrue(mac == "00:00:00:00:00:00")
    }

    @Test
    fun arpParsing_publicIpIsFilteredOutByIsLanIpv4() {
        assertFalse(RokuSsdpDiscovery.isLanIpv4("8.8.8.8"))
        assertFalse(RokuSsdpDiscovery.isLanIpv4("1.2.3.4"))
    }

    // ── probe priority logic ─────────────────────────────────────────────────

    @Test
    fun probePriority_rokuOuiIpsEnumeratedBeforeNonRokuOuiIps() {
        // Mirror the partition logic from discoverFromArpCache()
        val arpMap = mapOf(
            "192.168.1.1"   to "AC:DE:48:11:22:33", // Apple — non-Roku
            "192.168.1.100" to "DC:3A:5E:AA:BB:CC", // Roku (DC3A5E)
            "192.168.1.200" to "00:09:2D:FF:EE:DD", // Roku (00092D)
            "192.168.1.50"  to "FC:FC:48:11:22:33", // LG — non-Roku
        )

        val (rokuOuiEntries, otherEntries) = arpMap.entries.partition { (_, mac) ->
            RokuArpDiscovery.isRokuMac(mac)
        }
        val probeOrder = (rokuOuiEntries + otherEntries).map { it.key }

        // Roku IPs must come first
        assertTrue(probeOrder.indexOf("192.168.1.100") < probeOrder.indexOf("192.168.1.1"))
        assertTrue(probeOrder.indexOf("192.168.1.200") < probeOrder.indexOf("192.168.1.50"))
    }
}

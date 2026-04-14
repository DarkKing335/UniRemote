package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.util.DeviceIdUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "RokuArpDiscovery"

/**
 * ARP cache-based Roku discovery.
 *
 * Reads the kernel's ARP table from `/proc/net/arp` to get a list of IP addresses
 * that the device has recently communicated with on the LAN. This is dramatically
 * faster than probing all 254 hosts in a /24 subnet because:
 *   - The ARP cache usually contains 5–40 entries (only active LAN neighbors).
 *   - No TCP connection is needed — just reading a file.
 *   - We then only probe the subset of IPs that might be Roku (by MAC OUI or
 *     by confirming port 8060 responds).
 *
 * Known Roku hardware MAC OUI prefixes (first 3 octets of Wi-Fi MAC):
 *   00:09:2D, 8C:4C:5F, B8:3E:59, CC:6D:A0, DC:3A:5E, D4:E8:80,
 *   A8:45:E3, 08:05:81, 9C:8E:CD, BC:22:BC, 40:31:3C, E0:CB:4E
 *
 * This list is not exhaustive — we also probe IPs whose MAC is unknown or
 * doesn't match, because Roku TVs behind NAT/switches sometimes appear in
 * ARP with the gateway MAC.
 *
 * Usage: call [discoverFromArpCache] after SSDP and subnet-probe have both
 * failed (i.e. when Network Access setting = Disabled on the Roku).
 */
internal object RokuArpDiscovery {

    /**
     * Known Roku Wi-Fi MAC OUI prefixes (first 3 octets, uppercase no separators).
     * Source: Wireshark OUI database + Roku FCC filings.
     */
    private val ROKU_OUI_SET = setOf(
        "00092D", // Roku Inc (original)
        "8C4C5F", // Roku Inc
        "B83E59", // Roku Inc
        "CC6DA0", // Roku Inc
        "DC3A5E", // Roku Inc
        "D4E880", // Roku Inc
        "A845E3", // Roku Inc
        "080581", // Roku Inc
        "9C8ECD", // Roku Inc
        "BC22BC", // Roku Inc
        "40313C", // Roku Inc
        "E0CB4E", // Roku Inc
        "001632", // Roku Inc (older streaming sticks)
        "70DE77", // Roku Inc
        "F4F599", // Roku Inc
        "6CA535", // Roku Inc
        "384601", // Roku Inc (Express+)
        "182B05", // Roku Inc
        "C005C2", // Roku Inc
        "3C8F46", // Roku Inc
        "D86162", // Roku Inc
        "18B430", // Roku Inc
    )

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .writeTimeout(900, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Reads /proc/net/arp and returns (ip → mac) pairs for all complete entries.
     * Returns empty map on devices that don't allow /proc access (shouldn't happen on Android).
     */
    internal fun readArpCache(): Map<String, String> {
        return runCatching {
            val out = mutableMapOf<String, String>()
            File("/proc/net/arp").forEachLine { line ->
                // Format: IP-address  HW-type  Flags  HW-address  Mask  Device
                //         192.168.1.5 0x1      0x2    dc:3a:5e:.. *     wlan0
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 6) return@forEachLine
                val ip = cols[0]
                val flags = cols[2]  // 0x2 = ATF_COM (complete/reachable), 0x6 = also permanent
                val mac = cols[3]

                // Skip header row, incomplete entries (0x0), and loopback
                if (ip == "IP" || flags == "0x0" || mac == "00:00:00:00:00:00") return@forEachLine
                if (!RokuSsdpDiscovery.isLanIpv4(ip)) return@forEachLine

                out[ip] = mac.uppercase(Locale.US)
            }
            out
        }.getOrElse {
            Log.w(TAG, "Failed to read ARP cache: ${it.message}")
            emptyMap()
        }
    }

    /**
     * Returns the 3-octet OUI string (no separators) for a MAC address like "DC:3A:5E:AA:BB:CC".
     */
    internal fun macOui(mac: String): String {
        return mac.replace(":", "").replace("-", "").take(6).uppercase(Locale.US)
    }

    /**
     * Returns true if [mac] belongs to a known Roku OUI.
     */
    internal fun isRokuMac(mac: String): Boolean {
        if (mac.isBlank() || mac == "00:00:00:00:00:00") return false
        return macOui(mac) in ROKU_OUI_SET
    }

    /**
     * Discovers Roku devices by:
     * 1. Reading /proc/net/arp for active LAN neighbors.
     * 2. Prioritising IPs whose MAC matches a known Roku OUI.
     * 3. Probing ALL ARP entries on port 8060 (not just Roku-OUI ones) because
     *    Roku TVs may appear behind the gateway MAC on some router configs.
     *
     * @param maxProbeMs  Maximum wall-clock time budget for HTTP probes (ms).
     */
    fun discoverFromArpCache(maxProbeMs: Long = 4_000L): List<TvDevice> {
        val arpMap = readArpCache()
        if (arpMap.isEmpty()) {
            Log.d(TAG, "ARP cache is empty or unreadable")
            return emptyList()
        }

        Log.d(TAG, "ARP cache has ${arpMap.size} entries: ${arpMap.keys.take(5)}…")

        // Split into Roku-OUI (high priority probe first) vs unknown-OUI
        val (rokuOuiIps, otherIps) = arpMap.entries.partition { (_, mac) -> isRokuMac(mac) }
        val probeOrder = (rokuOuiIps + otherIps).map { it.key }

        val found = mutableListOf<TvDevice>()
        val deadline = System.currentTimeMillis() + maxProbeMs

        for (ip in probeOrder) {
            if (System.currentTimeMillis() >= deadline) {
                Log.d(TAG, "ARP probe deadline reached after probing ${found.size} devices")
                break
            }
            val mac = arpMap[ip].orEmpty()
            val device = probeRokuAtIp(ip, mac)
            if (device != null) {
                found += device
                Log.d(TAG, "ARP probe found Roku: $ip (${device.name})")
            }
        }

        return found
    }

    /**
     * Probes a single IP on the Roku ECP port.
     * Returns a [TvDevice] if the device responds as a Roku, null otherwise.
     */
    internal fun probeRokuAtIp(ip: String, macHint: String = ""): TvDevice? {
        val url = "http://$ip:${TvBrand.ROKU.defaultPort}/query/device-info"
        val req = Request.Builder().url(url).get().build()

        val responseProbe = runCatching {
            probeClient.newCall(req).execute().use { response ->
                Triple(response.code, response.header("Server"), response.body?.string())
            }
        }.getOrNull() ?: return null

        val statusCode = responseProbe.first
        val server = responseProbe.second
        val body = responseProbe.third

        val isRokuResponse = RokuSsdpDiscovery.isLikelyRokuHttpFingerprintForTest(
            statusCode = statusCode,
            serverHeader = server,
            body = body
        )

        if (!isRokuResponse) return null

        val parsed = body?.let {
            runCatching { RokuSsdpDiscovery.parseRokuDescriptionForTest(it) }.getOrNull()
        }

        val name = parsed?.friendlyName?.ifBlank { "Roku TV" } ?: "Roku TV"
        // Prefer MAC from ARP cache if XML didn't parse one
        val resolvedMac = parsed?.macAddress?.takeIf { it.isNotBlank() } ?: macHint
        val id = DeviceIdUtil.stableId(mac = resolvedMac, ip = ip, name = name)

        return TvDevice(
            id = id,
            name = name,
            brand = TvBrand.ROKU,
            ip = ip,
            mac = resolvedMac,
            port = TvBrand.ROKU.defaultPort
        )
    }
}

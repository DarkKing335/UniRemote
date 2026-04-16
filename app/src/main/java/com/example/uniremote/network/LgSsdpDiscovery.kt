package com.example.uniremote.network

import android.content.Context
import android.util.Log
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.toDomain
import com.example.uniremote.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

private const val TAG = "LgSsdpDiscovery"

/**
 * LG WebOS TV discovery via SSDP/UPnP.
 *
 * APK evidence (WebOSTVService.java):
 *   discoveryFilter() → new DiscoveryFilter(ID, "urn:lge-com:service:webos-second-screen:1")
 *
 * Flow:
 *   1. Send M-SEARCH with ST=urn:lge-com:service:webos-second-screen:1 to SSDP multicast
 *   2. Collect LOCATION URLs from responses
 *   3. Fetch UPnP device description XML from each LOCATION
 *   4. Parse friendlyName, modelName to build LG TvDevice
 *
 * LG TVs advertise SSDP but often do NOT advertise mDNS (_webostv._tcp),
 * which is why the NSD-based engine misses them on most Android networks.
 */
internal class LgSsdpDiscovery(
    context: Context,
    private val timeoutMs: Int = 2_500, // Reduced to 2.5s (since MX=2)
    private val rounds: Int = 3,
    private val retryDelayMs: Long = 1_000L // Faster retries
) {

    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)

    // APK: DiscoveryFilter → SSDP ST header value for LG WebOS
    private val SSDP_ST = "urn:lge-com:service:webos-second-screen:1"

    // LG also responds to the broader UPnP root device query on some firmware versions
    private val SSDP_ST_FALLBACK = "urn:dial-multiscreen-org:service:dial:1"

    fun discover(): Flow<List<TvDevice>> = flow {
        val devicesByIp = linkedMapOf<String, TvDevice>()

        // Pre-seed with cached LG devices from preferences so we don't show an
        // empty list while SSDP probes are in flight.
        cachedKnownLgDevices().forEach { cached ->
            devicesByIp.putIfAbsent(cached.ip, cached)
        }
        emit(devicesByIp.values.toList())

        repeat(rounds.coerceAtLeast(1)) { round ->
            val locations = probeLgLocations()
            
            // Parallel UPnP HTTP fetch to avoid sequential blocking
            coroutineScope {
                val deferreds = locations.map { (location, senderIp) ->
                    async(Dispatchers.IO) {
                        fetchLgDevice(location, senderIp)
                    }
                }
                val fetchedDevices = deferreds.awaitAll().filterNotNull()
                for (device in fetchedDevices) {
                    devicesByIp[device.ip] = device
                }
            }

            emit(devicesByIp.values.toList())

            if (round < rounds - 1) {
                delay(retryDelayMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Cached LG devices ────────────────────────────────────────────────────

    private suspend fun cachedKnownLgDevices(): List<TvDevice> {
        return runCatching {
            prefs.getKnownDevicesOnce()
                .map { it.toDomain() }
                .filter { it.brand == TvBrand.LG && isLanIpv4(it.ip) }
                .sortedByDescending { it.lastConnectedMs }
        }.onFailure {
            Log.w(TAG, "Failed to read cached LG devices", it)
        }.getOrDefault(emptyList())
    }

    // ── SSDP probe ───────────────────────────────────────────────────────────

    /**
     * Sends M-SEARCH request to SSDP multicast and collects all LOCATION responses
     * that look like LG WebOS TVs.
     *
     * Returns: Map<locationUrl, senderIp>
     */
    private fun probeLgLocations(): Map<String, String> {
        val locations = linkedMapOf<String, String>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.broadcast = true

                val destination = InetAddress.getByName("239.255.255.250")

                // Broadcast BOTH search targets immediately so we only wait for timeout ONCE
                listOf(SSDP_ST, SSDP_ST_FALLBACK).forEach { st ->
                    val searchRequest = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("\r\n")
                    }
                    val packet = DatagramPacket(
                        searchRequest.toByteArray(Charsets.UTF_8),
                        searchRequest.length,
                        destination,
                        1900
                    )
                    runCatching { socket.send(packet) }
                }

            val buffer = ByteArray(8 * 1024)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val receivePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(receivePacket)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }

                val payload = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                val headers = parseSsdpHeaders(payload)

                val responseSt = headers["st"]?.lowercase(Locale.US).orEmpty()
                val server    = headers["server"]?.lowercase(Locale.US).orEmpty()
                val usn       = headers["usn"]?.lowercase(Locale.US).orEmpty()
                val location  = headers["location"] ?: continue

                // Filter: must look like LG WebOS
                // Including fallback DIAL port check seamlessly since we sent both
                val looksLg = responseSt.contains("lge-com") ||
                              responseSt.contains("webos") ||
                              server.contains("lgwebostv") ||
                              server.contains("webos") ||
                              usn.contains("lge") ||
                              isLgDialPort(location)

                if (looksLg) {
                    val senderIp = receivePacket.address.hostAddress ?: continue
                    Log.d(TAG, "Found LG candidate: senderIp=$senderIp location=$location")
                    locations.putIfAbsent(location.trim(), senderIp)
                }
            } // Close while
            } // Close use
        }.onFailure {
            Log.w(TAG, "SSDP UDP binding failed: ${it.message}")
        }

        return locations
    }

    // Some LG TVs expose DIAL on port 1925 or 1926
    private fun isLgDialPort(location: String): Boolean {
        return runCatching {
            val url = URL(location)
            url.port in listOf(1925, 1926, 3000, 3001)
        }.getOrDefault(false)
    }

    // ── Fetch & parse UPnP description ───────────────────────────────────────

    /**
     * Fetches the UPnP device XML from the LOCATION URL and extracts
     * friendlyName, modelName, and optionally MAC address.
     */
    private fun fetchLgDevice(locationUrl: String, senderIp: String): TvDevice? {
        val xml = runCatching {
            val conn = URL(locationUrl).openConnection()
            conn.connectTimeout = 3_000
            conn.readTimeout    = 3_000
            conn.connect()
            conn.getInputStream().bufferedReader().readText()
        }.onFailure {
            Log.w(TAG, "Failed to fetch LG description from $locationUrl: ${it.message}")
        }.getOrNull() ?: return null

        val parsed = parseLgDescription(xml) ?: return null

        // Prefer IP from URL host; fall back to the datagram sender IP
        val parsedIp = runCatching { URL(locationUrl).host }.getOrNull().orEmpty()
        val ip = selectLanIp(parsedIp, senderIp) ?: return null

        // LG SSAP WebSocket is always port 3000 (ws) regardless of what SSDP says.
        // The controller upgrades to wss://ip:3001 automatically if needed.
        val port = TvBrand.LG.defaultPort  // 3000

        val name = parsed.friendlyName.ifBlank { parsed.modelName.ifBlank { "LG TV" } }
        val id   = DeviceIdUtil.stableId(mac = parsed.mac, ip = ip, name = name)

        Log.i(TAG, "Discovered LG TV: name=$name ip=$ip port=$port mac=${parsed.mac}")

        return TvDevice(
            id    = id,
            name  = name,
            brand = TvBrand.LG,
            ip    = ip,
            mac   = parsed.mac,
            port  = port
        )
    }

    // ── XML parser ───────────────────────────────────────────────────────────

    private data class LgDescription(
        val friendlyName: String,
        val modelName: String,
        val mac: String
    )

    private fun parseLgDescription(xml: String): LgDescription? {
        return runCatching {
            var friendlyName = ""
            var modelName    = ""
            var mac          = ""

            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

            val nodes = doc.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val node  = nodes.item(i)
                val tag   = node.nodeName.lowercase(Locale.US)
                val value = node.textContent?.trim().orEmpty()
                when (tag) {
                    "friendlyname" -> if (friendlyName.isBlank()) friendlyName = value
                    "modelname"    -> if (modelName.isBlank()) modelName = value
                    // LG description XML includes wifiMac or macAddress
                    "wifimac", "macaddress", "mac" -> {
                        val norm = normalizeMac(value)
                        if (norm != null) mac = norm
                    }
                }
            }

            LgDescription(friendlyName = friendlyName, modelName = modelName, mac = mac)
        }.onFailure {
            Log.w(TAG, "Failed to parse LG XML description: ${it.message}")
        }.getOrNull()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parseSsdpHeaders(payload: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        payload.lineSequence().forEach { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@forEach
            val key   = line.substring(0, index).trim().lowercase(Locale.US)
            val value = line.substring(index + 1).trim()
            map[key] = value
        }
        return map
    }

    private fun normalizeMac(raw: String): String? {
        val cleaned = raw.replace("-", "").replace(":", "").trim().uppercase(Locale.US)
        if (cleaned.length != 12) return null
        if (cleaned == "000000000000") return null
        return cleaned.chunked(2).joinToString(":")
    }

    private fun isLanIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return (nums[0] == 192 && nums[1] == 168) ||
               nums[0] == 10 ||
               (nums[0] == 172 && nums[1] in 16..31)
    }

    private fun selectLanIp(parsedIp: String?, senderIp: String?): String? {
        val parsed = parsedIp?.trim().orEmpty()
        val sender = senderIp?.trim().orEmpty()
        return when {
            isLanIpv4(parsed) -> parsed
            isLanIpv4(sender) -> sender
            else -> null
        }
    }
}

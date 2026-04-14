package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "RokuSsdpDiscovery"

/**
 * Roku discovery via a three-tier strategy:
 *
 * **Tier 1 – SSDP/UPnP (primary)**
 * - Sends M-SEARCH ST=roku:ecp to the multicast address.
 * - Parses LOCATION header and fetches device XML.
 * - Fastest when Roku Network Access = Permissive/Enabled.
 *
 * **Tier 2 – /24 subnet probe (first fallback)**
 * - When SSDP returns nothing, probes all 254 hosts in each local /24 on
 *   port 8060. Slower (254 concurrent TCP connects) but reliable when SSDP
 *   multicast is blocked by the router/AP.
 *
 * **Tier 3 – ARP cache probe (second fallback)**
 * - When both Tier 1 and Tier 2 fail (e.g. Roku Network Access = Disabled
 *   *and* the subnet probe misses due to rapid timeouts), reads the kernel
 *   ARP table from `/proc/net/arp` to get the 5–40 IPs of active LAN
 *   neighbors and probes only those IPs. Much faster than a full /24 scan.
 * - Prioritises IPs whose ARP MAC matches a known Roku hardware OUI.
 */
internal class RokuSsdpDiscovery(
    private val timeoutMs: Int = 3_500
) {

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .writeTimeout(700, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    internal data class ParsedRokuDescription(
        val friendlyName: String,
        val deviceType: String,
        val modelName: String,
        val macAddress: String?
    )

    fun discover(): Flow<List<TvDevice>> = flow {
        val devices = mutableListOf<TvDevice>()

        // Tier 1: SSDP multicast
        val locationToSenderIp = probeRokuLocations()
        for ((location, senderIp) in locationToSenderIp) {
            val parsed = fetchRokuDevice(location, senderIp)
            if (parsed != null) devices += parsed
        }

        // Tier 2: /24 subnet probe (when SSDP multicast is blocked by router/AP)
        if (devices.isEmpty()) {
            devices += discoverBySubnetProbe()
        }

        // Tier 3: ARP cache probe (when subnet probe is also empty — e.g. Roku
        // Network Access = Disabled, or subnet probe timed out too fast).
        // Uses /proc/net/arp to find the small set of active LAN neighbors and
        // probes only those, dramatically reducing the number of TCP attempts.
        if (devices.isEmpty()) {
            devices += discoverByArpCache()
        }

        emit(devices.distinctBy { it.id })
    }.flowOn(Dispatchers.IO)

    companion object {
        internal data class ManualHost(val ip: String, val port: Int)

        fun buildManualRokuDevice(ip: String, name: String = "Roku (Manual IP)"): TvDevice? {
            val parsed = parseManualHost(ip) ?: return null
            if (!isLanIpv4(parsed.ip)) return null

            val id = DeviceIdUtil.stableId(mac = "", ip = parsed.ip, name = name)
            return TvDevice(
                id = id,
                name = name,
                brand = TvBrand.ROKU,
                ip = parsed.ip,
                port = parsed.port
            )
        }

        internal fun parseManualHost(input: String): ManualHost? {
            val value = input.trim()
            if (value.isBlank()) return null

            val split = value.split(':')
            return when (split.size) {
                1 -> {
                    val ip = split[0].trim()
                    if (!isLanIpv4(ip)) return null
                    ManualHost(ip = ip, port = TvBrand.ROKU.defaultPort)
                }
                2 -> {
                    val ip = split[0].trim()
                    val port = split[1].trim().toIntOrNull() ?: return null
                    if (!isLanIpv4(ip)) return null
                    if (port !in 1..65535) return null
                    ManualHost(ip = ip, port = port)
                }
                else -> null
            }
        }

        fun isLanIpv4(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false

            return (nums[0] == 192 && nums[1] == 168) ||
                nums[0] == 10 ||
                (nums[0] == 172 && nums[1] in 16..31)
        }

        internal fun selectLanIp(parsedIp: String?, senderIp: String?): String? {
            val parsed = parsedIp?.trim().orEmpty()
            val sender = senderIp?.trim().orEmpty()
            return when {
                isLanIpv4(parsed) -> parsed
                isLanIpv4(sender) -> sender
                else -> null
            }
        }

        internal fun parseSsdpHeadersForTest(payload: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            payload.lineSequence().forEach { line ->
                val index = line.indexOf(':')
                if (index <= 0) return@forEach
                val key = line.substring(0, index).trim().lowercase(Locale.US)
                val value = line.substring(index + 1).trim()
                map[key] = value
            }
            return map
        }

        internal fun parseRokuDescriptionForTest(xml: String): ParsedRokuDescription {
            var friendlyName = ""
            var deviceType = ""
            var modelName = ""
            var macAddress: String? = null

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val tag = node.nodeName.lowercase(Locale.US)
                val value = node.textContent?.trim().orEmpty()
                when (tag) {
                    "friendlyname" -> if (friendlyName.isBlank()) friendlyName = value
                    "devicetype" -> if (deviceType.isBlank()) deviceType = value
                    "modelname" -> if (modelName.isBlank()) modelName = value
                    "wifi-mac", "macaddress", "mac" -> {
                        val normalized = normalizeMacForTest(value)
                        if (normalized != null) macAddress = normalized
                    }
                }
            }

            val resolvedName = when {
                friendlyName.isNotBlank() -> friendlyName
                modelName.isNotBlank() -> modelName
                deviceType.isNotBlank() -> "Roku ${deviceType.substringAfterLast(':')}"
                else -> "Roku TV"
            }

            return ParsedRokuDescription(
                friendlyName = resolvedName,
                deviceType = deviceType,
                modelName = modelName,
                macAddress = macAddress
            )
        }

        internal fun normalizeMacForTest(raw: String): String? {
            val cleaned = raw.replace("-", "").replace(":", "").trim().uppercase(Locale.US)
            if (cleaned.length != 12) return null
            if (cleaned == "000000000000") return null
            return cleaned.chunked(2).joinToString(":")
        }

        internal fun isLikelyRokuHttpFingerprintForTest(
            statusCode: Int,
            serverHeader: String?,
            body: String?
        ): Boolean {
            val server = serverHeader?.lowercase(Locale.US).orEmpty()
            val payload = body?.lowercase(Locale.US).orEmpty()
            val hasRokuHint =
                server.contains("roku") ||
                    payload.contains("roku") ||
                    payload.contains("ecp-version") ||
                    (payload.contains("serial-number") && payload.contains("software-version"))

            return when {
                statusCode in 200..299 -> hasRokuHint
                statusCode == 401 || statusCode == 403 -> hasRokuHint || server.contains("roku")
                else -> false
            }
        }
    }

    private fun probeRokuLocations(): Map<String, String> {
        val searchRequest = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: roku:ecp\r\n")
            append("\r\n")
        }

        val destination = InetAddress.getByName("239.255.255.250")
        val packet = DatagramPacket(
            searchRequest.toByteArray(Charsets.UTF_8),
            searchRequest.length,
            destination,
            1900
        )

        val locations = linkedMapOf<String, String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.broadcast = true

            runCatching { socket.send(packet) }
                .onFailure { Log.w(TAG, "Failed to send Roku SSDP probe", it) }

            val buffer = ByteArray(8 * 1024)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val receivePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(receivePacket)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }

                val payload = String(
                    receivePacket.data,
                    0,
                    receivePacket.length,
                    Charsets.UTF_8
                )

                val headers = parseSsdpHeadersForTest(payload)
                val st = headers["st"]?.lowercase(Locale.US).orEmpty()
                val server = headers["server"]?.lowercase(Locale.US).orEmpty()
                val location = headers["location"] ?: continue

                val looksRoku = st.contains("roku") || server.contains("roku")
                if (looksRoku) {
                    val senderIp = receivePacket.address.hostAddress ?: continue
                    locations.putIfAbsent(location.trim(), senderIp)
                }
            }
        }

        return locations
    }

    private fun fetchRokuDevice(locationUrl: String, senderIp: String): TvDevice? {
        val req = Request.Builder().url(locationUrl).get().build()
        val responseProbe = runCatching {
            NetworkClient.instance.newCall(req).execute().use { response ->
                Triple(response.code, response.header("Server"), response.body?.string())
            }
        }.getOrNull() ?: return null

        val statusCode = responseProbe.first
        val server = responseProbe.second
        val body = responseProbe.third

        if (!isLikelyRokuHttpFingerprintForTest(statusCode, server, body)) {
            return null
        }

        val parsed = body?.let {
            runCatching { parseRokuDescriptionForTest(it) }.getOrNull()
        }

        val parsedIp = runCatching { URL(locationUrl).host }.getOrNull().orEmpty()
        val ip = selectLanIp(parsedIp = parsedIp, senderIp = senderIp) ?: return null

        val name = parsed?.friendlyName?.ifBlank { "Roku TV" } ?: "Roku TV"
        val id = DeviceIdUtil.stableId(mac = "", ip = ip, name = name)

        return TvDevice(
            id = id,
            name = name,
            brand = TvBrand.ROKU,
            ip = ip,
            mac = parsed?.macAddress ?: "",
            port = TvBrand.ROKU.defaultPort
        )
    }

    private suspend fun discoverBySubnetProbe(): List<TvDevice> = coroutineScope {
        val prefixes = localLanPrefixes()
        if (prefixes.isEmpty()) return@coroutineScope emptyList()

        val candidates = prefixes.flatMap { prefix ->
            (1..254).map { host -> "$prefix.$host" }
        }.distinct()

        val deferred = candidates.map { ip ->
            async { probeRokuByIp(ip) }
        }
        deferred.awaitAll().filterNotNull().distinctBy { it.id }
    }

    private fun probeRokuByIp(ip: String): TvDevice? {
        if (!isLanIpv4(ip)) return null

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

        if (!isLikelyRokuHttpFingerprintForTest(statusCode, server, body)) {
            return null
        }

        val parsed = body?.let {
            runCatching { parseRokuDescriptionForTest(it) }.getOrNull()
        }
        val name = parsed?.friendlyName?.ifBlank { "Roku TV" } ?: "Roku TV"
        val id = DeviceIdUtil.stableId(mac = "", ip = ip, name = name)
        return TvDevice(
            id = id,
            name = name,
            brand = TvBrand.ROKU,
            ip = ip,
            mac = parsed?.macAddress ?: "",
            port = TvBrand.ROKU.defaultPort
        )
    }

    /**
     * Tier 3 fallback: ARP cache probe.
     *
     * When both SSDP multicast and /24 subnet probe fail — which happens when the
     * Roku "Network access" setting is Disabled — we fall back to reading the kernel
     * ARP table.  The ARP cache contains only hosts the phone has recently talked
     * to on the LAN (typically 5-40 entries), so this probe is much faster than
     * scanning all 254 /24 addresses and avoids spamming inactive hosts.
     *
     * Priority order:
     *  1. IPs whose ARP-cached MAC matches a known Roku OUI (probed first)
     *  2. All other ARP entries (in case the Roku is behind the same subnet switch
     *     and shows up with the router/gateway MAC)
     */
    private fun discoverByArpCache(): List<TvDevice> {
        Log.d(TAG, "Tier 3: trying ARP cache probe")
        val found = RokuArpDiscovery.discoverFromArpCache(maxProbeMs = 5_000L)
        Log.d(TAG, "Tier 3: ARP cache probe found ${found.size} Roku device(s)")
        return found
    }

    private fun localLanPrefixes(): Set<String> {
        val out = linkedSetOf<String>()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrElse { emptyList() }

        interfaces.forEach { iface ->
            if (!iface.isUp || iface.isLoopback) return@forEach
            val addrs = Collections.list(iface.inetAddresses)
            addrs.forEach { address ->
                val ip = address.hostAddress?.substringBefore('%') ?: return@forEach
                if (!isLanIpv4(ip)) return@forEach
                val parts = ip.split('.')
                if (parts.size == 4) {
                    out += "${parts[0]}.${parts[1]}.${parts[2]}"
                }
            }
        }

        return out
    }
}

package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

private const val TAG = "RokuSsdpDiscovery"

/**
 * Roku discovery over SSDP/UPnP.
 *
 * Flow:
 * 1. Send M-SEARCH with ST=roku:ecp to SSDP multicast.
 * 2. Parse LOCATION from responses.
 * 3. Fetch the UPnP device description XML.
 * 4. Build Roku [TvDevice] with friendly name and type.
 */
internal class RokuSsdpDiscovery(
    private val timeoutMs: Int = 3_500
) {

    internal data class ParsedRokuDescription(
        val friendlyName: String,
        val deviceType: String,
        val modelName: String,
        val macAddress: String?
    )

    fun discover(): Flow<List<TvDevice>> = flow {
        val locationToSenderIp = probeRokuLocations()
        if (locationToSenderIp.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val devices = mutableListOf<TvDevice>()
        for ((location, senderIp) in locationToSenderIp) {
            val parsed = fetchRokuDevice(location, senderIp)
            if (parsed != null) {
                devices += parsed
            }
        }

        emit(devices.distinctBy { it.id })
    }.flowOn(Dispatchers.IO)

    companion object {
        fun buildManualRokuDevice(ip: String, name: String = "Roku (Manual IP)"): TvDevice? {
            val normalizedIp = ip.trim()
            if (!isLanIpv4(normalizedIp)) return null

            val id = DeviceIdUtil.stableId(
                mac = "",
                ip = normalizedIp,
                name = name
            )

            return TvDevice(
                id = id,
                name = name,
                brand = TvBrand.ROKU,
                ip = normalizedIp,
                port = TvBrand.ROKU.defaultPort
            )
        }

        fun isLanIpv4(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false

            // Private LAN ranges with priority for 192.168.x.x
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
        val xml = runCatching {
            NetworkClient.instance.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull() ?: return null

        val parsed = parseRokuDescriptionForTest(xml)

        val parsedIp = runCatching { URL(locationUrl).host }.getOrNull().orEmpty()
        val ip = selectLanIp(parsedIp = parsedIp, senderIp = senderIp) ?: return null

        val name = parsed.friendlyName.ifBlank { "Roku TV" }
        val id = DeviceIdUtil.stableId(mac = "", ip = ip, name = name)
        val mac = parsed.macAddress ?: ""

        return TvDevice(
            id = id,
            name = name,
            brand = TvBrand.ROKU,
            ip = ip,
            mac = mac,
            port = TvBrand.ROKU.defaultPort
        )
    }

}

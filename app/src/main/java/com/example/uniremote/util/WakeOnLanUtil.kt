package com.example.uniremote.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Sends a Wake-on-LAN Magic Packet to wake a TV from standby.
 *
 * A magic packet is: 6 bytes of 0xFF followed by the target MAC address
 * repeated 16 times (total 102 bytes), sent via UDP broadcast to port 9.
 *
 * Requirements:
 *  - TV must have WoL enabled in its network settings.
 *  - Phone and TV must be on the same subnet.
 *  - CHANGE_WIFI_MULTICAST_STATE permission required.
 */
object WakeOnLanUtil {

    private const val PORT = 9
    private val relayClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * @param macAddress MAC address formatted as "AA:BB:CC:DD:EE:FF" or "AA-BB-CC-DD-EE-FF"
     * @param broadcastIp e.g. "192.168.1.255" (subnet broadcast address)
     */
    fun sendMagicPacket(macAddress: String, broadcastIp: String = "255.255.255.255") {
        val macBytes = parseMac(macAddress)
        val packet   = buildMagicPacket(macBytes)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(broadcastIp)
            socket.send(DatagramPacket(packet, packet.size, address, PORT))
        }
    }

    /**
     * Sends wake request to a WOLRelay-compatible endpoint.
     * Expected API: POST {"mac":"AA:BB:CC:DD:EE:FF"} to /wake.
     */
    fun sendMagicPacketViaRelay(macAddress: String, relayBaseUrl: String) {
        parseMac(macAddress) // Validate MAC format before making network request.
        val normalizedBase = relayBaseUrl.trim().trimEnd('/')
        require(normalizedBase.isNotBlank()) { "Relay URL is blank" }
        val wakeUrl = if (normalizedBase.endsWith("/wake", ignoreCase = true)) {
            normalizedBase
        } else {
            "$normalizedBase/wake"
        }

        val json = "{\"mac\":\"${formatMac(macAddress)}\"}"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(wakeUrl)
            .post(body)
            .build()

        relayClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("WOL relay failed with HTTP ${response.code}")
            }
        }
    }

    private fun parseMac(mac: String): ByteArray {
        val cleaned = mac.replace(":", "").replace("-", "").replace(".", "")
        require(cleaned.length == 12) { "Invalid MAC address: $mac" }
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun formatMac(mac: String): String {
        val cleaned = mac.replace(":", "").replace("-", "").replace(".", "").uppercase()
        require(cleaned.length == 12) { "Invalid MAC address: $mac" }
        return cleaned.chunked(2).joinToString(":")
    }

    private fun buildMagicPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(102)
        repeat(6) { packet[it] = 0xFF.toByte() }
        repeat(16) { rep -> macBytes.copyInto(packet, destinationOffset = 6 + rep * 6) }
        return packet
    }
}

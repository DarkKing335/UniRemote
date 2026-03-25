package com.example.uniremote.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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

    private fun parseMac(mac: String): ByteArray {
        val cleaned = mac.replace(":", "").replace("-", "").replace(".", "")
        require(cleaned.length == 12) { "Invalid MAC address: $mac" }
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun buildMagicPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(102)
        // First 6 bytes: 0xFF
        repeat(6) { packet[it] = 0xFF.toByte() }
        // Next 96 bytes: MAC repeated 16 times
        repeat(16) { rep ->
            macBytes.copyInto(packet, destinationOffset = 6 + rep * 6)
        }
        return packet
    }
}

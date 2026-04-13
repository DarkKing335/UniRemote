package com.example.uniremote.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * Active SSDP probe used for DLNA diagnostics on Android devices.
 */
internal class DlnaSsdpProbe {

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }

    fun runProbe(
        attempt: Int,
        timeoutMs: Int = 1800,
        log: (String) -> Unit
    ) {
        checkMulticastBind(log)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", 0))
                soTimeout = timeoutMs
            }

            val localPort = socket.localPort
            log("SSDP attempt=$attempt bind=0.0.0.0:$localPort st=$SEARCH_TARGET")

            val request = buildMSearchRequest()
            val requestBytes = request.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                requestBytes,
                requestBytes.size,
                InetAddress.getByName(SSDP_ADDRESS),
                SSDP_PORT
            )
            socket.send(packet)
            log("SSDP attempt=$attempt msearch sent")

            val deadline = System.currentTimeMillis() + timeoutMs
            var responseCount = 0
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(8192)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                } catch (_: Exception) {
                    break
                }

                val raw = String(
                    responsePacket.data,
                    0,
                    responsePacket.length,
                    Charsets.UTF_8
                )
                responseCount += 1
                log(
                    "SSDP raw attempt=$attempt from=${responsePacket.address.hostAddress}:${responsePacket.port}\n$raw"
                )
            }

            if (responseCount == 0) {
                log("SSDP attempt=$attempt timeout: no responses within ${timeoutMs}ms")
            } else {
                log("SSDP attempt=$attempt completed with responses=$responseCount")
            }
        } catch (t: Throwable) {
            log("SSDP attempt=$attempt failed: ${t::class.java.simpleName}: ${t.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun checkMulticastBind(log: (String) -> Unit) {
        var mSocket: MulticastSocket? = null
        try {
            mSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", SSDP_PORT))
            }
            log("SSDP multicast bind check: 0.0.0.0:$SSDP_PORT OK")
        } catch (t: Throwable) {
            log("SSDP multicast bind check failed on 0.0.0.0:$SSDP_PORT: ${t.message}")
        } finally {
            runCatching { mSocket?.close() }
        }
    }

    private fun buildMSearchRequest(): String {
        return (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $SEARCH_TARGET\r\n" +
                "USER-AGENT: UniRemote/1.0 UPnP/1.1 Android\r\n" +
                "\r\n"
            )
    }
}

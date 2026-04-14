package com.uniremote.roku.discovery

import com.uniremote.roku.model.RokuDevice
import com.uniremote.roku.model.RokuDeviceState
import com.uniremote.roku.utils.RokuLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RokuDiscoveryService {

    private val SSDP_IP = "239.255.255.250"
    private val SSDP_PORT = 1900
    private val M_SEARCH_PAYLOAD = "M-SEARCH * HTTP/1.1\r\n" +
            "Host: $SSDP_IP:$SSDP_PORT\r\n" +
            "Man: \"ssdp:discover\"\r\n" +
            "ST: roku:ecp\r\n\r\n"

    private val discoveredDevices = MutableStateFlow<List<RokuDevice>>(emptyList())
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startDiscovery(): Flow<List<RokuDevice>> {
        if (discoveryJob?.isActive == true) return discoveredDevices
        
        discoveryJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 5000 // 5 second timeout for listening wait

                val sendData = M_SEARCH_PAYLOAD.toByteArray()
                val address = InetAddress.getByName(SSDP_IP)
                val sendPacket = DatagramPacket(sendData, sendData.size, address, SSDP_PORT)

                // Heartbeat every 10 seconds (as requested)
                while (isActive) {
                    RokuLogger.i("Sending SSDP M-SEARCH for roku:ecp")
                    socket.send(sendPacket)

                    val receiveData = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)

                    try {
                        // Wait for response
                        while (true) {
                            socket.receive(receivePacket)
                            val response = String(receivePacket.data, 0, receivePacket.length)
                            handleSsdpResponse(response)
                        }
                    } catch (e: Exception) {
                        // Timeout naturally occurs every 5 seconds, ending the inner receive loop
                        // then we loop back and ping again due to heartbeat
                    }
                    
                    delay(10000) // 10 second heartbeat interval
                }
            } catch (e: Exception) {
                RokuLogger.e("SSDP Discovery Error", e)
            } finally {
                socket?.close()
            }
        }
        return discoveredDevices
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun handleSsdpResponse(response: String) {
        if (response.contains("roku:ecp")) {
            // Very simplified extraction for Location header
            val lines = response.split("\r\n")
            var locationUrl = ""
            var usn = ""
            for (line in lines) {
                if (line.startsWith("LOCATION:", ignoreCase = true)) {
                    locationUrl = line.substringAfter("LOCATION:").trim()
                } else if (line.startsWith("USN:", ignoreCase = true)) {
                    usn = line.substringAfter("USN:").trim()
                }
            }

            if (locationUrl.isNotEmpty()) {
                val ipAddress = extractIpFromLocation(locationUrl)
                val deviceId = usn.substringAfter("uuid:").substringBefore("::") // UUID format

                // Construct a temporary RokuDevice for cache (real device info comes from /query/device-info)
                val newDevice = RokuDevice(
                    deviceId = if (deviceId.isNotEmpty()) deviceId else ipAddress,
                    friendlyName = "Discovered Roku",
                    modelName = "Unknown Model",
                    serialNumber = "Unknown",
                    ipAddress = ipAddress,
                    port = 8060,
                    locationUrl = locationUrl,
                    state = RokuDeviceState.DISCOVERED_ONLY
                )

                updateDiscoveredDevices(newDevice)
            }
        }
    }

    private fun updateDiscoveredDevices(newDevice: RokuDevice) {
        val currentList = discoveredDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.deviceId == newDevice.deviceId || it.ipAddress == newDevice.ipAddress }
        if (index == -1) {
            currentList.add(newDevice)
            discoveredDevices.value = currentList
            RokuLogger.i("Discovered new Roku at ${newDevice.ipAddress}")
        }
    }

    private fun extractIpFromLocation(location: String): String {
        // http://192.168.1.100:8060/ -> 192.168.1.100
        return location.substringAfter("http://").substringBefore(":")
    }
}

package com.example.uniremote.data

/**
 * Represents a discovered or saved TV device.
 */
data class TvDevice(
    val id: String,            // stable: MAC if known, else hash(ip+name)
    val name: String,          // display name from discovery / user-set
    val brand: TvBrand,
    val ip: String,
    val mac: String = "",      // MAC address for Wake-on-LAN (empty = unknown)
    val port: Int = brand.defaultPort,
    val ssid: String = "",     // WiFi SSID at time of last connection
    val lastConnectedMs: Long = 0L
)

enum class TvBrand(val displayName: String, val defaultPort: Int) {
    SAMSUNG("Samsung",    8001),
    LG     ("LG",         3000),
    ANDROID("Android TV", 5555),
    UNKNOWN("Unknown TV", 8001)
}

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
    val lastConnectedMs: Long = 0L,
    val token: String? = null  // Auth token / pairing key (e.g. Samsung/LG WebOS)
)

enum class TvBrand(val displayName: String, val defaultPort: Int) {
    SAMSUNG   ("Samsung",    8001),
    LG        ("LG",         3000),
    SONY      ("Sony",       50001),  // Sony Bravia 
    ANDROID   ("Android TV", 6466),   // Generic Android TV
    GOOGLE_TV ("Google TV",  6466),   // Google TV Remote Protocol
    XIAOMI    ("Xiaomi",     6466),   // Xiaomi customized Android TV
    FIRE_TV   ("Fire TV",    5555),   // Amazon Fire TV
    ROKU      ("Roku",       8060),   // Roku REST API
    UNKNOWN   ("Unknown TV", 8001)
}

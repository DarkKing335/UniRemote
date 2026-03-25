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
    SAMSUNG  ("Samsung",    8001),
    LG       ("LG",         3000),
    ANDROID  ("Android TV", 5555),
    SONY     ("Sony",       8080), // Often uses Android TV but can have specific APIs
    TCL      ("TCL",       5555), // Usually Android TV
    XIAOMI   ("Xiaomi",    5555), // Usually Android TV
    HISENSE  ("Hisense",   5555), // Can be VIDAA or Android TV
    PANASONIC("Panasonic", 80),   // Viera Remote
    TOSHIBA  ("Toshiba",   5555),
    SHARP    ("Sharp",     5555),
    PHILIPS  ("Philips",   5555),
    ROKU     ("Roku",       8060),
    VIZIO    ("Vizio",      7345),
    UNKNOWN  ("Unknown TV", 8001)
}

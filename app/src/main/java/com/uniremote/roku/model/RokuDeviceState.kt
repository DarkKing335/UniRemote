package com.uniremote.roku.model

enum class RokuDeviceState {
    DISCOVERED_ONLY,   // SSDP found it, but no ECP check yet
    CONTROL_AVAILABLE, // Port 8060 responds -> fully controllable
    BLOCKED,           // Port 8060 returns 403 or timeout -> Network Access disabled
    UNREACHABLE        // Device disappeared from network
}

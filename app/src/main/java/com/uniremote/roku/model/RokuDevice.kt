package com.uniremote.roku.model

data class RokuDevice(
    val deviceId: String,
    val friendlyName: String,
    val modelName: String,
    val serialNumber: String,
    val ipAddress: String,
    val port: Int = 8060,
    val locationUrl: String,
    var state: RokuDeviceState = RokuDeviceState.DISCOVERED_ONLY
)

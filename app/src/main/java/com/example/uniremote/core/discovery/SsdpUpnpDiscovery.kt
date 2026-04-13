package com.example.uniremote.core.discovery

import com.example.uniremote.core.device.CastDevice
import kotlinx.coroutines.flow.StateFlow

interface SsdpUpnpDiscovery {
    val devices: StateFlow<List<CastDevice>>

    fun startDiscovery()
    fun refreshDiscovery()
    fun stopDiscovery()
}

package com.example.uniremote.viewmodel

import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.network.RemoteControlDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class DiscoveryCoordinator(
    private val scope: CoroutineScope,
    private val discovery: RemoteControlDiscovery,
    private val repo: DeviceRepository
) {
    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private var discoveryJob: Job? = null

    fun scanDevices() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            try {
                withTimeout(10_000L) {
                    discovery.discover().collect { devices ->
                        _discoveredDevices.value = devices
                        repo.updateScanResults(devices.map { it.id }.toSet(), scanComplete = false)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Expected end-of-scan signal after the configured window.
            } finally {
                val finalIds = _discoveredDevices.value.map { it.id }.toSet()
                repo.updateScanResults(finalIds, scanComplete = true)
            }
        }
    }

    fun stopScan() {
        discoveryJob?.cancel()
        discoveryJob = null
    }
}

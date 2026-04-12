package com.example.uniremote.domain

import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.network.DeviceConnectionManager
import kotlinx.coroutines.delay

/**
 * Clean Architecture Domain Use Case: Handles the logic of automatically
 * iterating through known devices, attempting connection, handling retries,
 * and updating the offline status in the database.
 */
class AutoConnectUseCase(
    private val repository: DeviceRepository,
    private val connectionManager: DeviceConnectionManager
) {
    suspend operator fun invoke(): Boolean {
        val candidates = repository.getAutoConnectCandidates()
        if (candidates.isEmpty()) return false

        for (device in candidates) {
            // Skip Google TV / Android TV / Sony / Xiaomi devices that have never been
            // successfully paired. These require an interactive PIN pairing flow and must
            // NOT be silently connected via ADB — doing so bypasses the pairing requirement
            // and causes the PIN to never appear on subsequent manual connect attempts.
            if (connectionManager.requiresPairing(device) && !device.isPaired) {
                continue
            }

            if (connectionManager.tryConnectSilently(device)) {
                return true
            }
            // Single retry with back-off — avoids hammering the network on transient failures
            delay(500L)
            if (connectionManager.tryConnectSilently(device)) {
                return true
            }
            // Mark unreachable after both attempts failed
            repository.markDeviceOffline(device.id)
        }

        connectionManager.setDisconnected()
        return false
    }
}


package com.example.uniremote.domain

import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.network.DeviceConnectionManager

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
            if (connectionManager.tryConnectSilently(device)) {
                return true
            }
            // Single retry
            if (connectionManager.tryConnectSilently(device)) {
                return true
            }
            // Mark unreachable
            repository.markDeviceOffline(device.id)
        }

        connectionManager.setDisconnected()
        return false
    }
}

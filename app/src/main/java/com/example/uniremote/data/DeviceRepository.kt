package com.example.uniremote.data

import android.content.Context
import android.util.Log
import com.example.uniremote.util.WifiUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "DeviceRepository"

/**
 * Business-logic layer for device persistence.
 *
 * All data stored in DataStore (no Room/KSP required).
 * SSID is attached HERE when saving, not in DeviceDiscovery.
 */
class DeviceRepository(
    private val context: Context,
    private val prefs: AppPreferences
) {

    /** Flow of all saved known devices, most-recently-connected first. */
    val knownDevices: Flow<List<TvDevice>> = prefs.knownDevices.map { list ->
        list.map { it.toDomain() }
    }

    // ── Auto-connect candidates ───────────────────────────────────────────────

    /**
     * Returns devices matching the current WiFi SSID,
     * sorted descending by lastConnectedMs.
     */
    suspend fun getAutoConnectCandidates(): List<TvDevice> {
        val ssid = WifiUtil.getCurrentSsid(context)
        val known = prefs.getKnownDevicesOnce()
            .sortedByDescending { it.lastConnectedMs }

        if (known.isEmpty()) return emptyList()

        // Primary strategy: exact SSID match.
        if (!ssid.isNullOrBlank()) {
            Log.d(TAG, "Auto-connect: looking for devices on SSID \"$ssid\"")
            val onCurrentSsid = known.filter { it.ssid == ssid }
            if (onCurrentSsid.isNotEmpty()) {
                return onCurrentSsid.map { it.toDomain() }
            }

            // If none match current SSID, still try devices that have no SSID persisted.
            val withoutSsid = known.filter { it.ssid.isBlank() }
            if (withoutSsid.isNotEmpty()) {
                Log.d(TAG, "Auto-connect: no exact SSID match, trying devices without stored SSID")
                return withoutSsid.map { it.toDomain() }
            }
        } else {
            Log.d(TAG, "Auto-connect: SSID unavailable, falling back to recent known devices")
        }

        // Final fallback: try a few most-recent devices to avoid startup misses
        // when SSID is temporarily unavailable right after app launch.
        return known.take(3).map { it.toDomain() }
    }

    // ── Save / update ─────────────────────────────────────────────────────────

    /** Persist a successfully connected device; reads current SSID automatically. */
    suspend fun saveDevice(device: TvDevice, nowMs: Long = System.currentTimeMillis()) {
        val ssid = WifiUtil.getCurrentSsid(context) ?: ""
        prefs.upsertKnownDevice(device.toKnownDevice(ssid = ssid, nowMs = nowMs, isOnline = true))
        Log.d(TAG, "Saved device: ${device.name} on SSID=\"$ssid\"")
    }

    // ── Online / Offline ──────────────────────────────────────────────────────

    suspend fun markDeviceOnline(id: String)  = prefs.markKnownDeviceOnline(id)
    suspend fun markDeviceOffline(id: String) = prefs.markKnownDeviceOffline(id)

    // ── Delete ────────────────────────────────────────────────────────────────

    suspend fun forgetDevice(id: String) {
        prefs.deleteKnownDevice(id)
        Log.d(TAG, "Forgot device: $id")
    }

    // ── Update scan visibility ────────────────────────────────────────────────

    /**
     * Mark known devices seen in scan as online.
     * Only marks unseen devices as offline when [scanComplete] is true —
     * i.e. after the full scan window has elapsed — to avoid the
     * offline→online→offline flicker caused by the sequential mDNS resolve queue.
     */
    suspend fun updateScanResults(scannedIds: Set<String>, scanComplete: Boolean = false) {
        val ssid   = WifiUtil.getCurrentSsid(context) ?: return
        val onSsid = prefs.getKnownDevicesOnce().filter { it.ssid == ssid }
        onSsid.forEach { entity ->
            if (scannedIds.contains(entity.id)) {
                prefs.markKnownDeviceOnline(entity.id)
            } else if (scanComplete) {
                // Only mark offline once the scan is declared complete, not mid-discovery
                prefs.markKnownDeviceOffline(entity.id)
            }
        }
    }
}

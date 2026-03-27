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
        if (ssid == null) {
            Log.d(TAG, "No WiFi SSID — skipping auto-connect")
            return emptyList()
        }
        Log.d(TAG, "Auto-connect: looking for devices on SSID \"$ssid\"")
        return prefs.getKnownDevicesOnce()
            .filter { it.ssid == ssid }
            .sortedByDescending { it.lastConnectedMs }
            .map { it.toDomain() }
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

    /** Mark known devices seen in scan as online; others on same SSID as offline. */
    suspend fun updateScanResults(scannedIds: Set<String>) {
        val ssid    = WifiUtil.getCurrentSsid(context) ?: return
        val onSsid  = prefs.getKnownDevicesOnce().filter { it.ssid == ssid }
        onSsid.forEach { entity ->
            if (scannedIds.contains(entity.id)) {
                prefs.markKnownDeviceOnline(entity.id)
            } else {
                prefs.markKnownDeviceOffline(entity.id)
            }
        }
    }
}

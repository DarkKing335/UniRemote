package com.example.uniremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_LAST_DEVICE_ID    = stringPreferencesKey("last_device_id")
        private val KEY_LAST_DEVICE_NAME  = stringPreferencesKey("last_device_name")
        private val KEY_LAST_DEVICE_IP    = stringPreferencesKey("last_device_ip")
        private val KEY_LAST_DEVICE_MAC   = stringPreferencesKey("last_device_mac")
        private val KEY_LAST_DEVICE_PORT  = intPreferencesKey("last_device_port")
        private val KEY_LAST_DEVICE_BRAND = stringPreferencesKey("last_device_brand")
        private val KEY_AUTO_RECONNECT    = booleanPreferencesKey("auto_reconnect")
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: true
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_RECONNECT] = enabled }
    }

    // ── Last connected device ─────────────────────────────────────────────────

    val lastDevice: Flow<TvDevice?> = context.dataStore.data.map { prefs ->
        val ip = prefs[KEY_LAST_DEVICE_IP] ?: return@map null
        TvDevice(
            id    = prefs[KEY_LAST_DEVICE_ID]    ?: ip,
            name  = prefs[KEY_LAST_DEVICE_NAME]  ?: "TV",
            brand = TvBrand.valueOf(prefs[KEY_LAST_DEVICE_BRAND] ?: TvBrand.UNKNOWN.name),
            ip    = ip,
            mac   = prefs[KEY_LAST_DEVICE_MAC]   ?: "",
            port  = prefs[KEY_LAST_DEVICE_PORT]  ?: 8001
        )
    }

    suspend fun saveLastDevice(device: TvDevice) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_DEVICE_ID]    = device.id
            prefs[KEY_LAST_DEVICE_NAME]  = device.name
            prefs[KEY_LAST_DEVICE_IP]    = device.ip
            prefs[KEY_LAST_DEVICE_MAC]   = device.mac
            prefs[KEY_LAST_DEVICE_PORT]  = device.port
            prefs[KEY_LAST_DEVICE_BRAND] = device.brand.name
        }
    }

    suspend fun clearLastDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_DEVICE_ID)
            prefs.remove(KEY_LAST_DEVICE_NAME)
            prefs.remove(KEY_LAST_DEVICE_IP)
            prefs.remove(KEY_LAST_DEVICE_MAC)
            prefs.remove(KEY_LAST_DEVICE_PORT)
            prefs.remove(KEY_LAST_DEVICE_BRAND)
        }
    }

    // Convenience: synchronous read for use in ViewModel init
    suspend fun getAutoReconnectOnce(): Boolean = autoReconnect.first()
    suspend fun getLastDeviceOnce(): TvDevice?  = lastDevice.first()
}

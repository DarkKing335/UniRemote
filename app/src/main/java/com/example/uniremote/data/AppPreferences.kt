package com.example.uniremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.uniremote.network.TvKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        // Legacy single-device keys (kept for migration compatibility)
        private val KEY_LAST_DEVICE_ID    = stringPreferencesKey("last_device_id")
        private val KEY_LAST_DEVICE_NAME  = stringPreferencesKey("last_device_name")
        private val KEY_LAST_DEVICE_IP    = stringPreferencesKey("last_device_ip")
        private val KEY_LAST_DEVICE_MAC   = stringPreferencesKey("last_device_mac")
        private val KEY_LAST_DEVICE_PORT  = intPreferencesKey("last_device_port")
        private val KEY_LAST_DEVICE_BRAND = stringPreferencesKey("last_device_brand")

        private val KEY_AUTO_RECONNECT    = booleanPreferencesKey("auto_reconnect")
        private val KEY_USER_MACROS       = stringPreferencesKey("user_macros")
        private val KEY_KNOWN_DEVICES     = stringPreferencesKey("known_devices")

        // Field separators – must NOT appear in device names/IPs/SSIDs
        private const val DEVICE_SEP  = "|||"  // between devices
        private const val FIELD_SEP   = "^^"   // between fields of one device
        // Macro separators
        private const val MACRO_SEP   = "~~~"
        private const val MACRO_FIELD = "^^"
        private const val KEY_SEP     = ","
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: true
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_RECONNECT] = enabled }
    }

    suspend fun getAutoReconnectOnce(): Boolean = autoReconnect.first()

    // ── Legacy single last-device (used in ViewModel for LG pairing) ──────────

    val lastDevice: Flow<TvDevice?> = context.dataStore.data.map { prefs ->
        val ip = prefs[KEY_LAST_DEVICE_IP] ?: return@map null
        TvDevice(
            id    = prefs[KEY_LAST_DEVICE_ID]    ?: ip,
            name  = prefs[KEY_LAST_DEVICE_NAME]  ?: "TV",
            brand = runCatching { TvBrand.valueOf(prefs[KEY_LAST_DEVICE_BRAND] ?: "") }.getOrElse { TvBrand.UNKNOWN },
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

    suspend fun getLastDeviceOnce(): TvDevice? = lastDevice.first()

    // ── Known devices (multi-device persistence) ──────────────────────────────

    val knownDevices: Flow<List<KnownDevice>> = context.dataStore.data.map { prefs ->
        deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "")
    }

    suspend fun getKnownDevicesOnce(): List<KnownDevice> = knownDevices.first()

    suspend fun upsertKnownDevice(device: KnownDevice) {
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.id == device.id }
            if (idx >= 0) current[idx] = device else current.add(device)
            // Keep at most 20 known devices, sorted by lastConnectedMs DESC
            val trimmed = current.sortedByDescending { it.lastConnectedMs }.take(20)
            prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(trimmed)
        }
    }

    suspend fun markKnownDeviceOffline(id: String) {
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(isOnline = false, lastSeenMs = System.currentTimeMillis())
                prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(current)
            }
        }
    }

    suspend fun markKnownDeviceOnline(id: String) {
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(isOnline = true, lastSeenMs = System.currentTimeMillis())
                prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(current)
            }
        }
    }

    suspend fun deleteKnownDevice(id: String) {
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            current.removeAll { it.id == id }
            prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(current)
        }
    }

    // ── Known device serialization ────────────────────────────────────────────
    // Format per device: id^^name^^brand^^ip^^mac^^port^^ssid^^lastConnectedMs^^lastSeenMs^^isOnline

    private fun serializeKnownDevices(list: List<KnownDevice>): String =
        list.joinToString(DEVICE_SEP) { d ->
            listOf(d.id, d.name, d.brand, d.ip, d.mac, d.port.toString(),
                d.ssid, d.lastConnectedMs.toString(), d.lastSeenMs.toString(),
                if (d.isOnline) "1" else "0"
            ).joinToString(FIELD_SEP)
        }

    private fun deserializeKnownDevices(raw: String): List<KnownDevice> {
        if (raw.isBlank()) return emptyList()
        return raw.split(DEVICE_SEP).mapNotNull { entry ->
            val p = entry.split(FIELD_SEP)
            if (p.size < 10) return@mapNotNull null
            KnownDevice(
                id              = p[0],
                name            = p[1],
                brand           = p[2],
                ip              = p[3],
                mac             = p[4],
                port            = p[5].toIntOrNull() ?: 8001,
                ssid            = p[6],
                lastConnectedMs = p[7].toLongOrNull() ?: 0L,
                lastSeenMs      = p[8].toLongOrNull() ?: 0L,
                isOnline        = p[9] == "1"
            )
        }
    }

    // ── User macros ───────────────────────────────────────────────────────────

    val userMacros: Flow<List<UserMacro>> = context.dataStore.data.map { prefs ->
        deserializeMacros(prefs[KEY_USER_MACROS] ?: "")
    }

    suspend fun saveMacro(macro: UserMacro) {
        context.dataStore.edit { prefs ->
            val current = deserializeMacros(prefs[KEY_USER_MACROS] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.id == macro.id }
            if (idx >= 0) current[idx] = macro else current.add(macro)
            prefs[KEY_USER_MACROS] = serializeMacros(current)
        }
    }

    suspend fun deleteMacro(macroId: String) {
        context.dataStore.edit { prefs ->
            val current = deserializeMacros(prefs[KEY_USER_MACROS] ?: "").toMutableList()
            current.removeAll { it.id == macroId }
            prefs[KEY_USER_MACROS] = serializeMacros(current)
        }
    }

    private fun serializeMacros(list: List<UserMacro>): String =
        list.joinToString(MACRO_SEP) { m ->
            listOf(m.id, m.name, m.description, m.icon,
                m.keys.joinToString(KEY_SEP) { it.name }
            ).joinToString(MACRO_FIELD)
        }

    private fun deserializeMacros(raw: String): List<UserMacro> {
        if (raw.isBlank()) return emptyList()
        return raw.split(MACRO_SEP).mapNotNull { entry ->
            val parts = entry.split(MACRO_FIELD)
            if (parts.size < 5) return@mapNotNull null
            val keys = parts[4].split(KEY_SEP).mapNotNull { keyName ->
                runCatching { TvKey.valueOf(keyName) }.getOrNull()
            }
            UserMacro(id = parts[0], name = parts[1], description = parts[2], icon = parts[3], keys = keys)
        }
    }

    // ── Default macro seeding ─────────────────────────────────────────────────

    private val KEY_DEFAULTS_SEEDED = booleanPreferencesKey("defaults_seeded")

    /**
     * Inserts 4 factory macros the very first time the app runs.
     * Safe to call every launch – no-op after first seed thanks to the flag.
     */
    suspend fun seedDefaultMacros() {
        val alreadySeeded = context.dataStore.data.first()[KEY_DEFAULTS_SEEDED] == true
        if (alreadySeeded) return

        val defaults = listOf(
            UserMacro(
                id = "default_movie_night", name = "Movie Night",
                description = "Home → Netflix → Play", icon = "nightlight",
                keys = listOf(TvKey.HOME, TvKey.NETFLIX, TvKey.OK)
            ),
            UserMacro(
                id = "default_gaming", name = "Gaming Mode",
                description = "HDMI 2 → Mute → OK", icon = "game",
                keys = listOf(TvKey.HDMI_2, TvKey.MUTE, TvKey.OK)
            ),
            UserMacro(
                id = "default_evening", name = "Evening Chill",
                description = "Home → YouTube → OK", icon = "play",
                keys = listOf(TvKey.HOME, TvKey.YOUTUBE, TvKey.OK)
            ),
            UserMacro(
                id = "default_night", name = "Night Cycle",
                description = "Mute → Vol down → Power", icon = "bedtime",
                keys = listOf(TvKey.MUTE, TvKey.VOL_DOWN, TvKey.VOL_DOWN, TvKey.VOL_DOWN,
                               TvKey.VOL_DOWN, TvKey.VOL_DOWN, TvKey.POWER)
            )
        )

        context.dataStore.edit { prefs ->
            if (deserializeMacros(prefs[KEY_USER_MACROS] ?: "").isEmpty()) {
                prefs[KEY_USER_MACROS] = serializeMacros(defaults)
            }
            prefs[KEY_DEFAULTS_SEEDED] = true
        }
    }
}

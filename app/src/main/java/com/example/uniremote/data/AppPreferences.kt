package com.example.uniremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.uniremote.network.TvKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {
    private val gson = Gson()
    private val secureStore = SecureCredentialStore(context)

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

    suspend fun getAutoReconnectOnce(): Boolean {
        return context.dataStore.data.map { prefs -> prefs[KEY_AUTO_RECONNECT] ?: true }.first()
    }

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

    suspend fun getLastDeviceOnce(): TvDevice? {
        return context.dataStore.data.map { prefs ->
            val ip = prefs[KEY_LAST_DEVICE_IP] ?: return@map null
            TvDevice(
                id    = prefs[KEY_LAST_DEVICE_ID]    ?: ip,
                name  = prefs[KEY_LAST_DEVICE_NAME]  ?: "TV",
                brand = runCatching { TvBrand.valueOf(prefs[KEY_LAST_DEVICE_BRAND] ?: "") }.getOrElse { TvBrand.UNKNOWN },
                ip    = ip,
                mac   = prefs[KEY_LAST_DEVICE_MAC]   ?: "",
                port  = prefs[KEY_LAST_DEVICE_PORT]  ?: 8001
            )
        }.first()
    }

    // ── Known devices (multi-device persistence) ──────────────────────────────

    val knownDevices: Flow<List<KnownDevice>> = context.dataStore.data.map { prefs ->
        deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").map { attachSecureToken(it) }
    }

    suspend fun getKnownDevicesOnce(): List<KnownDevice> {
        return context.dataStore.data.map { prefs ->
            deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").map { attachSecureToken(it) }
        }.first()
    }

    suspend fun upsertKnownDevice(device: KnownDevice) {
        if (!device.token.isNullOrBlank()) {
            secureStore.putDeviceToken(device.id, device.token)
        }
        val persisted = device.copy(token = null)
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.id == persisted.id }
            if (idx >= 0) current[idx] = persisted else current.add(persisted)
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
        secureStore.removeDeviceToken(id)
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "").toMutableList()
            current.removeAll { it.id == id }
            prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(current)
        }
    }

    suspend fun migrateKnownDeviceTokensToSecureStore() {
        context.dataStore.edit { prefs ->
            val current = deserializeKnownDevices(prefs[KEY_KNOWN_DEVICES] ?: "")
            if (current.none { !it.token.isNullOrBlank() }) {
                return@edit
            }

            current.forEach { device ->
                if (!device.token.isNullOrBlank()) {
                    secureStore.putDeviceToken(device.id, device.token)
                }
            }

            val sanitized = current.map { it.copy(token = null) }
            prefs[KEY_KNOWN_DEVICES] = serializeKnownDevices(sanitized)
        }
    }

    // ── Known device serialization ────────────────────────────────────────────

    private fun serializeKnownDevices(list: List<KnownDevice>): String = gson.toJson(list)

    private fun deserializeKnownDevices(raw: String): List<KnownDevice> {
        if (raw.isBlank()) return emptyList()
        if (!raw.trim().startsWith("[")) {
            return deserializeKnownDevicesLegacy(raw)
        }
        return try {
            val type = object : TypeToken<List<KnownDevice>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun deserializeKnownDevicesLegacy(raw: String): List<KnownDevice> {
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

    private fun attachSecureToken(device: KnownDevice): KnownDevice {
        val encryptedToken = secureStore.getDeviceToken(device.id)
        if (!encryptedToken.isNullOrBlank()) {
            return device.copy(token = encryptedToken)
        }
        if (!device.token.isNullOrBlank()) {
            secureStore.putDeviceToken(device.id, device.token)
            return device.copy(token = device.token)
        }
        return device.copy(token = null)
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

    private fun serializeMacros(list: List<UserMacro>): String = gson.toJson(list)

    private fun deserializeMacros(raw: String): List<UserMacro> {
        if (raw.isBlank()) return emptyList()
        if (!raw.trim().startsWith("[")) {
            return deserializeMacrosLegacy(raw)
        }
        return try {
            val type = object : TypeToken<List<UserMacro>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun deserializeMacrosLegacy(raw: String): List<UserMacro> {
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

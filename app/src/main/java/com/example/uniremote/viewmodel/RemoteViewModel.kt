package com.example.uniremote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.network.DeviceDiscovery
import com.example.uniremote.network.RemoteControllerFactory
import com.example.uniremote.network.TvAppUiModel
import com.example.uniremote.network.TvController
import com.example.uniremote.network.TvKey
import com.example.uniremote.network.toUiModel
import com.example.uniremote.util.WakeOnLanUtil
import com.example.uniremote.util.WifiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG       = "RemoteViewModel"
private const val CONNECT_TIMEOUT_MS = 4_000L   // per-device connection timeout
private const val STARTUP_DELAY_MS   = 1_000L   // delay before auto-connect on launch

// ── Connection status ─────────────────────────────────────────────────────────
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    object Offline      : ConnectionStatus()   // TV is off / unreachable
    data class Error(val message: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private val prefs = AppPreferences(application)
    private val repo  = DeviceRepository(
        context = application,
        prefs   = prefs
    )
    private val discovery = DeviceDiscovery(application)

    // ── State ─────────────────────────────────────────────────────────────────

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<TvDevice?>(null)
    val connectedDevice: StateFlow<TvDevice?> = _connectedDevice.asStateFlow()

    /** Currently active devices in the multitasking bar. */
    private val _activeDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val activeDevices: StateFlow<List<TvDevice>> = _activeDevices.asStateFlow()

    /** If true, all commands are sent to all active devices simultaneously. */
    private val _isBroadcastMode = MutableStateFlow(false)
    val isBroadcastMode: StateFlow<Boolean> = _isBroadcastMode.asStateFlow()

    fun toggleBroadcastMode() { _isBroadcastMode.value = !_isBroadcastMode.value }

    /** All known (previously connected) devices from Room DB. */
    val knownDevices: StateFlow<List<TvDevice>> = repo.knownDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Current Wi-Fi SSID displayed in SettingsScreen. */
    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _installedApps = MutableStateFlow<List<TvAppUiModel>>(emptyList())
    val installedApps: StateFlow<List<TvAppUiModel>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    /** Cached results from the most recent NSD scan. */
    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    /**
     * True when the TV is currently showing a text-input field.
     * Set to true by TV controllers that can detect IME state (e.g. Android TV ADB).
     * Defaults to false; user can manually toggle from KeyboardLayout.
     */
    private val _isTextInputActive = MutableStateFlow(false)
    val isTextInputActive: StateFlow<Boolean> = _isTextInputActive.asStateFlow()

    fun setTextInputActive(active: Boolean) { _isTextInputActive.value = active }

    val autoReconnect: Flow<Boolean> = prefs.autoReconnect

    /** User-defined macros persisted in DataStore. */
    val userMacros: StateFlow<List<UserMacro>> = prefs.userMacros
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    // Internal
    private val controllers = mutableMapOf<String, TvController>() // DeviceID to Controller
    private val controller: TvController?
        get() {
            val device = _connectedDevice.value ?: return null
            return controllers[device.id]
        }


    private var discoveryJob: Job? = null
    private var macroJob: Job? = null

    // ── Init: startup delay + auto-connect + seed defaults ─────────────────────
    init {
        viewModelScope.launch {
            prefs.seedDefaultMacros()                      // ✅ insert 4 defaults if first run
            _currentSsid.value = WifiUtil.getCurrentSsid(application)
            val shouldReconnect = prefs.getAutoReconnectOnce()
            if (shouldReconnect) {
                delay(STARTUP_DELAY_MS) // ✅ Slight delay so UI loads first
                autoConnect()
            }
        }
    }

    // ── Auto-Connect Logic ────────────────────────────────────────────────────

    /**
     * Tries to connect to known devices matching the current Wi-Fi SSID,
     * sorted by most recently connected (lastConnectedMs DESC).
     *
     * For each candidate:
     *  1. Try to connect with [CONNECT_TIMEOUT_MS] ms timeout
     *  2. If fails → retry once before moving to next
     *  3. If all fail → show manual list (ConnectionStatus.Disconnected)
     */
    suspend fun autoConnect() {
        val candidates = repo.getAutoConnectCandidates()
        if (candidates.isEmpty()) {
            Log.d(TAG, "Auto-connect: no candidates for current SSID")
            return
        }
        Log.d(TAG, "Auto-connect: ${candidates.size} candidate(s)")

        for (device in candidates) {
            Log.d(TAG, "Auto-connect: trying ${device.name} (${device.ip})")

            // First attempt
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { tryConnect(device) }
            if (ok == true) {
                Log.i(TAG, "Auto-connect: connected to ${device.name}")
                return
            }

            // ✅ Retry once before skipping
            Log.d(TAG, "Auto-connect: retrying ${device.name}…")
            val retry = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { tryConnect(device) }
            if (retry == true) {
                Log.i(TAG, "Auto-connect: connected to ${device.name} on retry")
                return
            }

            // Mark this device offline since we couldn't reach it
            viewModelScope.launch { repo.markDeviceOffline(device.id) }
            Log.w(TAG, "Auto-connect: ${device.name} unreachable, trying next…")
        }

        // All candidates exhausted
        Log.w(TAG, "Auto-connect: all candidates failed, waiting for manual selection")
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    /** Internal: attempts connection, returns true on success. Non-throwing. */
    private suspend fun tryConnect(device: TvDevice): Boolean {
        return runCatching {
            controllers[device.id]?.disconnect()
            val c = buildController(device)
            controllers[device.id] = c
            c.connect()
        }.getOrElse {
            Log.w(TAG, "tryConnect error: ${it.message}")
            false
        }
    }

    // ── Public: connect to a specific device ──────────────────────────────────

    fun connectTo(device: TvDevice) {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Connecting
            _connectedDevice.value  = device
            
            if (!_activeDevices.value.any { it.id == device.id }) {
                _activeDevices.value += device
            }

            controllers[device.id]?.disconnect()
            val c = buildController(device)
            controllers[device.id] = c

            val success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runCatching { c.connect() }.getOrElse { false }
            } ?: false

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
                repo.saveDevice(device)
                repo.markDeviceOnline(device.id)
                loadInstalledApps()
            } else {
                _connectionStatus.value = ConnectionStatus.Error("Cannot reach ${device.name}. Check that the TV is on and reachable.")
                repo.markDeviceOffline(device.id)
                controllers.remove(device.id)
            }
        }
    }

    fun selectDevice(device: TvDevice) {
        _connectedDevice.value = device
        // Update connection status based on the selected device's controller
        val isAlive = controllers[device.id]?.isConnected() ?: false
        _connectionStatus.value = if (isAlive) ConnectionStatus.Connected else ConnectionStatus.Disconnected
        loadInstalledApps()
    }

    fun disconnect() {
        macroJob?.cancel(); macroJob = null
        val device = _connectedDevice.value ?: return
        controllers[device.id]?.disconnect()
        controllers.remove(device.id)
        _activeDevices.value = _activeDevices.value.filter { it.id != device.id }
        
        if (_activeDevices.value.isNotEmpty()) {
            selectDevice(_activeDevices.value.first())
        } else {
            _connectionStatus.value = ConnectionStatus.Disconnected
            _connectedDevice.value  = null
        }
        
        viewModelScope.launch { repo.markDeviceOffline(device.id) }
    }

    /** Remove a device from the known-devices list permanently. */
    fun forgetDevice(deviceId: String) {
        viewModelScope.launch { repo.forgetDevice(deviceId) }
    }

    // ── Remote actions ────────────────────────────────────────────────────────

    fun sendKey(key: TvKey) {
        viewModelScope.launch(Dispatchers.IO) {
            val targets = if (_isBroadcastMode.value) controllers.values else listOfNotNull(controllers[_connectedDevice.value?.id])
            targets.forEach { target ->
                runCatching { target.sendKey(key) }
                    .onFailure { Log.e(TAG, "sendKey failed for ${target.device.name}: ${it.message}") }
            }
        }
    }

    fun sendTextAndEnter(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val targets = if (_isBroadcastMode.value) controllers.values else listOfNotNull(controller)
            targets.forEach { target ->
                runCatching {
                    target.sendText(text)
                    delay(300)
                    target.sendKey(TvKey.OK)
                }.onFailure {
                    Log.e(TAG, "sendTextAndEnter failed for ${target.device.name}: ${it.message}")
                }
            }
        }
    }

    fun moveMouse(dx: Float, dy: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val targets = if (_isBroadcastMode.value) controllers.values else listOfNotNull(controller)
            targets.forEach { target ->
                runCatching { target.moveMouse(dx, dy) }
                    .onFailure { e ->
                        Log.e(TAG, "moveMouse failed for ${target.device.name}: ${e.message}")
                    }
            }
        }
    }

    fun tapMouse() {
        viewModelScope.launch(Dispatchers.IO) {
            val targets = if (_isBroadcastMode.value) controllers.values else listOfNotNull(controller)
            targets.forEach { target ->
                runCatching { target.tapMouse() }
                    .onFailure { e ->
                        Log.e(TAG, "tapMouse failed for ${target.device.name}: ${e.message}")
                    }
            }
        }
    }

    fun volumeUp()    = sendKey(TvKey.VOL_UP)
    fun volumeDown()  = sendKey(TvKey.VOL_DOWN)
    fun mute()        = sendKey(TvKey.MUTE)
    fun channelUp()   = sendKey(TvKey.CH_UP)
    fun channelDown() = sendKey(TvKey.CH_DOWN)
    fun power()       = sendKey(TvKey.POWER)

    // ── Wake-on-LAN ───────────────────────────────────────────────────────────

    fun wakeTV() {
        val device = _connectedDevice.value ?: return
        if (!isValidMac(device.mac)) {
            _connectionStatus.value = ConnectionStatus.Error("Invalid or missing MAC address for WoL")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WakeOnLanUtil.sendMagicPacket(device.mac) }
                .onFailure { Log.e(TAG, "WoL failed: ${it.message}") }
        }
    }

    private fun isValidMac(mac: String): Boolean {
        val c = mac.replace(":", "").replace("-", "")
        return c.length == 12 && c.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    // ── App management ────────────────────────────────────────────────────────

    fun loadInstalledApps() {
        val device = _connectedDevice.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            _installedApps.value = runCatching { controllers[device.id]?.getInstalledApps() ?: emptyList() }
                .getOrElse { emptyList() }.map { it.toUiModel() }
            _isLoadingApps.value = false
        }
    }

    fun launchApp(appId: String) {
        val device = _connectedDevice.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controllers[device.id]?.launchApp(appId) }
        }
    }

    // ── Device discovery (NSD scan) ───────────────────────────────────────────

    fun scanDevices() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { devices ->
                _discoveredDevices.value = devices          // ✅ cached scan results
                // Update online status in DB for known devices
                repo.updateScanResults(devices.map { it.id }.toSet())
            }
        }
    }

    fun stopScan() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    // ── Screen mirroring ──────────────────────────────────────────────────────

    fun toggleMirroring() {
        _isMirroring.value = !_isMirroring.value
        Log.d(TAG, if (_isMirroring.value) "Mirroring started (stub)" else "Mirroring stopped")
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoReconnect(enabled) }
    }

    // ── Macro execution ───────────────────────────────────────────────────────

    fun runMacro(keys: List<TvKey>) {
        macroJob?.cancel()
        macroJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = if (_isBroadcastMode.value) controllers.values else listOfNotNull(controller)
            for (key in keys) {
                if (targets.isEmpty()) break
                targets.forEach { target ->
                    runCatching { target.sendKey(key) }
                }
                delay(200)
            }
        }
    }

    // ── User macro CRUD ───────────────────────────────────────────────────────

    fun saveMacro(macro: UserMacro) = viewModelScope.launch { prefs.saveMacro(macro) }
    fun deleteMacro(macroId: String) = viewModelScope.launch { prefs.deleteMacro(macroId) }
    fun runUserMacro(macroId: String) {
        userMacros.value.firstOrNull { it.id == macroId }?.let { runMacro(it.keys) }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    // ── Internals ─────────────────────────────────────────────────────────────
    
    private fun buildController(device: TvDevice): TvController {
        return RemoteControllerFactory.create(device)
    }

    override fun onCleared() {
        macroJob?.cancel()
        controllers.values.forEach { it.disconnect() }
        controllers.clear()
        stopScan()
        super.onCleared()
    }
}

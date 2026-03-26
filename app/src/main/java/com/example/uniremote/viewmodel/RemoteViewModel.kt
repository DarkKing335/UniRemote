package com.example.uniremote.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.network.*
import com.example.uniremote.network.PairingState
import com.example.uniremote.util.WifiUtil
import com.example.uniremote.util.WakeOnLanUtil
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

    /** All known (previously connected) devices from Room DB. */
    val knownDevices: StateFlow<List<TvDevice>> = repo.knownDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Current WiFi SSID displayed in SettingsScreen. */
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

    private val _lgPairingKey     = MutableStateFlow<String?>(null)

    /** Google TV pairing state — observed by UI to show/hide PIN dialog. */
    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // Internal
    private var controller: TvController? = null
    private var discoveryJob: Job? = null
    private var macroJob: Job? = null
    private var connectJob: Job? = null
    /** Holds the GoogleTvController while pairing is in progress. */
    private var googleTvPairingCtrl: GoogleTvController? = null

    // ── Init: startup delay + auto-connect + seed defaults ─────────────────────
    init {
        // Ensure Dadb has a writable home directory for its ~/.android/adbkey
        System.setProperty("user.home", application.filesDir.absolutePath)

        connectJob = viewModelScope.launch {
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
     * Tries to connect to known devices matching the current WiFi SSID,
     * sorted by most recently connected (lastConnectedMs DESC).
     *
     * For each candidate:
     *  1. Try connect with [CONNECT_TIMEOUT_MS] ms timeout
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
                _connectedDevice.value = device
                _connectionStatus.value = ConnectionStatus.Connected
                loadInstalledApps()
                return
            }

            // ✅ Retry once before skipping
            Log.d(TAG, "Auto-connect: retrying ${device.name}…")
            val retry = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { tryConnect(device) }
            if (retry == true) {
                Log.i(TAG, "Auto-connect: connected to ${device.name} on retry")
                _connectedDevice.value = device
                _connectionStatus.value = ConnectionStatus.Connected
                loadInstalledApps()
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
            controller?.disconnect()
            controller = buildController(device)
            controller!!.connect()
        }.getOrElse {
            Log.w(TAG, "tryConnect error: ${it.message}")
            false
        }
    }

    // ── Public: connect to a specific device ──────────────────────────────────

    fun connectTo(device: TvDevice) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Connecting
            _connectedDevice.value  = device

            controller?.disconnect()
            controller = buildController(device)

            val success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runCatching { controller!!.connect() }.getOrElse { false }
            } ?: false

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
                repo.saveDevice(device)
                repo.markDeviceOnline(device.id)
                loadInstalledApps()
            } else {
                _connectionStatus.value = ConnectionStatus.Error("Cannot reach ${device.name}. Check that the TV is on and reachable.")
                repo.markDeviceOffline(device.id)
                controller = null
            }
        }
    }

    // ── Google TV Pairing ──────────────────────────────────────────────────────

    /**
     * Starts the Google TV pairing flow for [device].
     * When the TV shows a 6-char PIN, [pairingState] moves to WAITING_FOR_PIN.
     * Call [submitPairingPin] with the code shown on screen.
     */
    fun startGoogleTvPairing(device: TvDevice) {
        viewModelScope.launch {
            _pairingState.value = PairingState.IDLE
            val ctrl = GoogleTvController(device) { state -> _pairingState.value = state }
            googleTvPairingCtrl = ctrl
            val ok = ctrl.pair()
            if (ok) {
                // Pairing done — now open control session
                connectTo(device)
            } else {
                _toastMessage.emit("Pairing failed. Make sure the TV is on and try again.")
            }
        }
    }

    /** Submit the 6-char PIN shown on the TV screen. */
    fun submitPairingPin(pin: String) {
        viewModelScope.launch { googleTvPairingCtrl?.submitPin(pin) }
    }

    /** Dismiss pinDialog without completing pairing. */
    fun cancelPairing() {
        _pairingState.value = PairingState.IDLE
        googleTvPairingCtrl = null
    }

    fun disconnect() {
        macroJob?.cancel(); macroJob = null
        connectJob?.cancel(); connectJob = null
        val id = _connectedDevice.value?.id
        controller?.disconnect(); controller = null
        _connectionStatus.value = ConnectionStatus.Disconnected
        _connectedDevice.value  = null
        id?.let { viewModelScope.launch { repo.markDeviceOffline(it) } }
    }

    /** Remove a device from the known-devices list permanently. */
    fun forgetDevice(deviceId: String) {
        viewModelScope.launch { repo.forgetDevice(deviceId) }
    }

    // ── Remote actions ────────────────────────────────────────────────────────

    fun sendKey(key: TvKey) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.sendKey(key) }
                .onFailure {
                    Log.e(TAG, "sendKey failed: ${it.message}")
                    _connectionStatus.value = ConnectionStatus.Error("Remote key failed. TV may have disconnected.")
                }
        }
    }

    fun sendText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.sendText(text) }
                .onFailure {
                    Log.e(TAG, "sendText failed: ${it.message}")
                    _toastMessage.emit(it.message ?: "Không gửi được văn bản.")
                }
        }
    }

    fun sendTextAndEnter(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                controller?.sendText(text)
                delay(300)
                controller?.sendKey(TvKey.OK)
            }.onFailure {
                Log.e(TAG, "sendTextAndEnter failed: ${it.message}")
                _toastMessage.emit(it.message ?: "Không gửi được văn bản.")
            }
        }
    }

    fun moveMouse(dx: Float, dy: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.moveMouse(dx, dy) }
                .onFailure { e ->
                    Log.e(TAG, "moveMouse failed: ${e.message}")
                    if (e is UnsupportedOperationException) {
                        _toastMessage.emit(e.message ?: "Thiết bị không hỗ trợ điều khiển chuột.")
                    }
                }
        }
    }

    fun tapMouse() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.tapMouse() }
                .onFailure { e ->
                    Log.e(TAG, "tapMouse failed: ${e.message}")
                    if (e is UnsupportedOperationException) {
                        _toastMessage.emit(e.message ?: "Thiết bị không hỗ trợ điều khiển chuột.")
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
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            _installedApps.value = runCatching { controller?.getInstalledApps() ?: emptyList() }
                .getOrElse { emptyList() }.map { it.toUiModel() }
            _isLoadingApps.value = false
        }
    }

    fun launchApp(appId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.launchApp(appId) }
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
            for (key in keys) {
                if (controller == null || _connectionStatus.value !is ConnectionStatus.Connected) break
                runCatching { controller?.sendKey(key) }
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

    private fun buildController(device: TvDevice): TvController = when (device.brand) {
        TvBrand.SAMSUNG   -> SamsungTvController(device)
        TvBrand.LG        -> LgWebOsController(
            device               = device,
            savedPairingKey      = _lgPairingKey.value,
            onPairingKeyReceived = { key ->
                _lgPairingKey.value = key
                viewModelScope.launch { prefs.saveLastDevice(device) }
            }
        )
        TvBrand.SONY      -> AndroidTvController(device)   // Sony Bravia = Android TV
        TvBrand.ANDROID   -> AndroidTvController(device)   // ADB-based
        TvBrand.GOOGLE_TV -> GoogleTvController(device) { state -> _pairingState.value = state }
        TvBrand.ROKU      -> RokuController(device)
        TvBrand.UNKNOWN   -> SamsungTvController(device)
    }

    override fun onCleared() {
        macroJob?.cancel()
        controller?.disconnect()
        stopScan()
        super.onCleared()
    }
}

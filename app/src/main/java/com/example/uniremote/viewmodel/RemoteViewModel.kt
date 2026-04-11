package com.example.uniremote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.domain.AutoConnectUseCase
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.network.*
import com.example.uniremote.util.WakeOnLanUtil
import com.example.uniremote.util.WifiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val STARTUP_DELAY_MS = 1_000L

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val repo  = DeviceRepository(application, prefs)
    private val discovery = DeviceDiscovery(application)

    // Architecture Fix: Decoupled God-Object TvController management into a dedicated manager class
    private val connectionManager = DeviceConnectionManager()

    private val autoConnectUseCase = AutoConnectUseCase(repo, connectionManager)

    // Lifted flows from connectionManager
    val connectionStatus: StateFlow<ConnectionStatus> = connectionManager.connectionStatus
    val connectedDevice: StateFlow<TvDevice?> = connectionManager.connectedDevice
    val pairingState: StateFlow<PairingState> = connectionManager.pairingState

    val knownDevices: StateFlow<List<TvDevice>> = repo.knownDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _installedApps = MutableStateFlow<List<TvAppUiModel>>(emptyList())
    val installedApps: StateFlow<List<TvAppUiModel>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    // Cast integration is not implemented in this build.
    private val _isCastFeatureAvailable = MutableStateFlow(false)
    val isCastFeatureAvailable: StateFlow<Boolean> = _isCastFeatureAvailable.asStateFlow()

    private val _isTextInputActive = MutableStateFlow(false)
    val isTextInputActive: StateFlow<Boolean> = _isTextInputActive.asStateFlow()

    // Exposed so MacroCard can show real execution state instead of local heuristic timer
    private val _isMacroRunning = MutableStateFlow(false)
    val isMacroRunning: StateFlow<Boolean> = _isMacroRunning.asStateFlow()

    // Tracks which specific macro ID is currently executing — used to highlight the correct card
    private val _currentMacroId = MutableStateFlow<String?>(null)
    val currentMacroId: StateFlow<String?> = _currentMacroId.asStateFlow()

    fun setTextInputActive(active: Boolean) { _isTextInputActive.value = active }

    val autoReconnect: Flow<Boolean> = prefs.autoReconnect

    val userMacros: StateFlow<List<UserMacro>> = prefs.userMacros
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toastMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var discoveryJob: Job? = null
    private var macroJob: Job? = null
    private var connectJob: Job? = null

    init {
        connectionManager.onTokenReceived = { token ->
            viewModelScope.launch {
                val currentDevice = connectedDevice.value
                if (currentDevice != null) {
                    val updated = currentDevice.copy(token = token)
                    repo.saveDevice(updated)
                }
            }
        }
        // NOTE: System.setProperty("user.home") has been moved to MainActivity.onCreate()
        // to ensure it is set before any controller is instantiated.

        connectJob = viewModelScope.launch(Dispatchers.IO) {
            prefs.seedDefaultMacros()
            _currentSsid.value = WifiUtil.getCurrentSsid(application)
            if (prefs.getAutoReconnectOnce()) {
                delay(STARTUP_DELAY_MS)
                autoConnect()
            }
        }
    }

    private suspend fun autoConnect() {
        val connected = autoConnectUseCase()
        if (connected) {
            loadInstalledApps()
        } else {
            _toastMessage.tryEmit("Không tìm thấy TV quen thuộc nào bật gần đây. Vui lòng chọn kết nối thủ công.")
        }
    }

    /**
     * Smart connect:
     * - For Google TV family (Sony/Android TV/Google TV/Xiaomi): runs the PIN pairing flow
     *   only if the device has never been successfully paired (isPaired == false).
     *   On subsequent launches the stored isPaired flag lets us skip the PIN entirely.
     * - For all other brands (Samsung/LG/Roku/Fire TV): connects directly.
     */
    fun connectOrPair(device: TvDevice) {
        val savedDevice = knownDevices.value.firstOrNull { it.id == device.id }
        // Use the persisted isPaired flag, not just "is in DB". A device can appear
        // in knownDevices after a scan without ever having completed pairing.
        val alreadyPaired = savedDevice?.isPaired == true
        if (connectionManager.requiresPairing(device) && !alreadyPaired) {
            startGoogleTvPairing(device)
        } else {
            connectTo(device)
        }
    }

    fun connectTo(device: TvDevice) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val success = connectionManager.connectTo(device)
            if (success) {
                // Bug fix: only save the base device info here.
                // The token will be saved separately by onTokenReceived() once the TV sends it
                // (which happens asynchronously after connect returns).
                // Saving 'device' here with token=null would overwrite a previously saved token.
                repo.markDeviceOnline(device.id)
                // If device has no saved record yet, save a minimal entry without overwriting token
                val known = knownDevices.value.firstOrNull { it.id == device.id }
                if (known == null) {
                    repo.saveDevice(device)  // First time only — safe because token is null anyway
                }
                loadInstalledApps()

                // Warn the user when the connection fell back to ADB, because ADB shell
                // commands will silently fail unless the TV approved the RSA fingerprint.
                if (connectionManager.isAdbFallbackMode.value) {
                    _toastMessage.tryEmit(
                        "Ket noi qua ADB. Neu remote khong phan hoi, hay vao TV → Developer Options → bat ADB over Network va chap nhan fingerprint."
                    )
                }
            } else {
                repo.markDeviceOffline(device.id)
                _toastMessage.tryEmit("Khong the ket noi voi ${device.name}. Vui long kiem tra lai TV.")
            }
        }
    }

    fun startGoogleTvPairing(device: TvDevice) {
        viewModelScope.launch {
            val paired = connectionManager.startGoogleTvPairing(device)
            if (paired) {
                // Persist isPaired = true so future launches skip the PIN entirely.
                val pairedDevice = device.copy(isPaired = true)
                repo.saveDevice(pairedDevice)
                connectTo(pairedDevice)
            } else {
                // Pairing failed (user cancelled, PIN timeout, or network error).
                // Still attempt a direct control connection — the TV may already trust
                // our RSA key if it was previously paired at OS/developer level.
                // Without isPaired=true, ADB will NOT be offered as a fallback,
                // so if the direct attempt also fails, the user sees a clear error.
                _toastMessage.tryEmit(
                    "Ghep doi that bai hoac bi huy. Thu ket noi truc tiep..."
                )
                connectTo(device)
            }
        }
    }

    fun submitPairingPin(pin: String) = viewModelScope.launch { connectionManager.submitPairingPin(pin) }
    fun cancelPairing() = connectionManager.cancelPairing()

    fun disconnect() {
        macroJob?.cancel(); macroJob = null
        connectJob?.cancel(); connectJob = null
        viewModelScope.launch {
            connectedDevice.value?.id?.let { repo.markDeviceOffline(it) }
            connectionManager.disconnect()
        }
    }

    fun forgetDevice(deviceId: String) = viewModelScope.launch { repo.forgetDevice(deviceId) }

    fun sendKey(key: TvKey) = viewModelScope.launch {
        runCatching { connectionManager.sendKey(key) }
            .onFailure { _toastMessage.tryEmit("Tín hiệu bị mất, chờ tivi phản hồi.") }
    }

    fun sendText(text: String) = viewModelScope.launch {
        runCatching { connectionManager.sendText(text) }
            .onFailure { _toastMessage.tryEmit(it.message ?: "Lỗi gửi phím cứng") }
    }

    fun sendTextAndEnter(text: String) = viewModelScope.launch {
        runCatching {
            connectionManager.sendText(text)
            delay(300)
            connectionManager.sendKey(TvKey.OK)
        }.onFailure { _toastMessage.tryEmit("Lỗi gửi đoạn văn bản") }
    }

    fun moveMouse(dx: Float, dy: Float) = viewModelScope.launch {
        runCatching { connectionManager.moveMouse(dx, dy) }
            .onFailure { if (it is UnsupportedOperationException) _toastMessage.tryEmit("TV này không hỗ trợ di chuột") }
    }

    fun tapMouse() = viewModelScope.launch {
        runCatching { connectionManager.tapMouse() }
            .onFailure { if (it is UnsupportedOperationException) _toastMessage.tryEmit("TV này không hỗ trợ click chuột") }
    }

    fun volumeUp()    = sendKey(TvKey.VOL_UP)
    fun volumeDown()  = sendKey(TvKey.VOL_DOWN)
    fun mute()        = sendKey(TvKey.MUTE)
    fun channelUp()   = sendKey(TvKey.CH_UP)
    fun channelDown() = sendKey(TvKey.CH_DOWN)
    fun power()       = sendKey(TvKey.POWER)

    fun wakeTV() {
        val device = connectedDevice.value ?: return
        val cleanMac = device.mac.replace(":", "").replace("-", "")
        if (cleanMac.length != 12) {
            _toastMessage.tryEmit("Thiếu địa chỉ MAC hợp lệ để bật TV bằng WoL")
            return
        }
        viewModelScope.launch(Dispatchers.IO) { WakeOnLanUtil.sendMagicPacket(device.mac) }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = runCatching {
                connectionManager.getInstalledApps().map { it.toUiModel() }
            }.getOrElse { emptyList() }
            _isLoadingApps.value = false
        }
    }

    fun launchApp(appId: String) = viewModelScope.launch {
        runCatching { connectionManager.launchApp(appId) }
    }

    fun scanDevices() {
        discoveryJob?.cancel()
        // Fix: tie the 10s completion timer INSIDE the same Job as the discovery flow.
        // Previously, a second call to scanDevices() would cancel only the discovery loop
        // but leave the old timer running, causing stale updateScanResults after 10s.
        discoveryJob = viewModelScope.launch {
            launch {
                discovery.discover().collect { devices ->
                    _discoveredDevices.value = devices
                    repo.updateScanResults(devices.map { it.id }.toSet(), scanComplete = false)
                }
            }
            // After 10s, this whole Job is the scan scope — timer and discovery cancelled together
            delay(10_000L)
            val finalIds = _discoveredDevices.value.map { it.id }.toSet()
            repo.updateScanResults(finalIds, scanComplete = true)
        }
    }

    fun stopScan() { discoveryJob?.cancel(); discoveryJob = null }

    fun requestCastFeatureInfo() {
        _toastMessage.tryEmit("Casting and mirroring are not available in this build yet.")
    }

    fun toggleMirroring() {
        if (!_isCastFeatureAvailable.value) {
            _isMirroring.value = false
            requestCastFeatureInfo()
            return
        }

        if (_isMirroring.value) {
            _isMirroring.value = false
        } else {
            _toastMessage.tryEmit("Mirroring session handling is not wired yet.")
        }
    }

    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch { prefs.setAutoReconnect(enabled) }

    fun runMacro(keys: List<TvKey>, macroId: String? = null) {
        macroJob?.cancel()
        macroJob = viewModelScope.launch(Dispatchers.IO) {
            _isMacroRunning.value = true
            _currentMacroId.value = macroId
            try {
                for (key in keys) {
                    ensureActive()
                    if (connectionStatus.value !is ConnectionStatus.Connected) break
                    runCatching { connectionManager.sendKey(key) }
                    delay(200)
                }
            } finally {
                // Always clear both flags, even on cancellation or error
                _isMacroRunning.value = false
                _currentMacroId.value = null
            }
        }
    }

    fun saveMacro(macro: UserMacro) = viewModelScope.launch { prefs.saveMacro(macro) }
    fun deleteMacro(macroId: String) = viewModelScope.launch { prefs.deleteMacro(macroId) }
    fun runUserMacro(macroId: String) {
        userMacros.value.firstOrNull { it.id == macroId }?.let { runMacro(it.keys, macroId) }
    }

    override fun onCleared() {
        macroJob?.cancel()
        connectJob?.cancel()
        connectionManager.onCleared()
        stopScan()
        super.onCleared()
    }
}

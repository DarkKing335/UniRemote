package com.example.uniremote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.network.*
import com.example.uniremote.util.WifiUtil
import com.example.uniremote.util.WakeOnLanUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.uniremote.domain.AutoConnectUseCase

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

    private val _isTextInputActive = MutableStateFlow(false)
    val isTextInputActive: StateFlow<Boolean> = _isTextInputActive.asStateFlow()

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
        System.setProperty("user.home", application.filesDir.absolutePath)

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

    fun connectTo(device: TvDevice) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val success = connectionManager.connectTo(device)
            if (success) {
                repo.saveDevice(device)
                repo.markDeviceOnline(device.id)
                loadInstalledApps()
            } else {
                repo.markDeviceOffline(device.id)
                _toastMessage.tryEmit("Không thể kết nối với ${device.name}. Vui lòng kiểm tra lại TV.")
            }
        }
    }

    fun startGoogleTvPairing(device: TvDevice) {
        viewModelScope.launch {
            if (connectionManager.startGoogleTvPairing(device)) {
                connectTo(device)
            } else {
                _toastMessage.tryEmit("Ghép nối chuẩn thất bại. Đang thử kết nối dự phòng...")
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
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { devices ->
                _discoveredDevices.value = devices
                repo.updateScanResults(devices.map { it.id }.toSet())
            }
        }
    }

    fun stopScan() { discoveryJob?.cancel(); discoveryJob = null }
    fun toggleMirroring() { _isMirroring.value = !_isMirroring.value }
    fun setAutoReconnect(enabled: Boolean) = viewModelScope.launch { prefs.setAutoReconnect(enabled) }

    fun runMacro(keys: List<TvKey>) {
        macroJob?.cancel()
        macroJob = viewModelScope.launch(Dispatchers.IO) {
            for (key in keys) {
                if (connectionStatus.value !is ConnectionStatus.Connected) break
                runCatching { connectionManager.sendKey(key) }
                delay(200)
            }
        }
    }

    fun saveMacro(macro: UserMacro) = viewModelScope.launch { prefs.saveMacro(macro) }
    fun deleteMacro(macroId: String) = viewModelScope.launch { prefs.deleteMacro(macroId) }
    fun runUserMacro(macroId: String) { 
        userMacros.value.firstOrNull { it.id == macroId }?.let { runMacro(it.keys) } 
    }

    override fun onCleared() {
        macroJob?.cancel()
        connectJob?.cancel()
        connectionManager.onCleared()
        stopScan()
        super.onCleared()
    }
}

package com.example.uniremote.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "RemoteViewModel"

// ── Connection status ─────────────────────────────────────────────────────────
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    object Offline      : ConnectionStatus()   // TV is off / unreachable
    data class Error(val message: String) : ConnectionStatus()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────
class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val discovery = DeviceDiscovery(application)

    // ── State ─────────────────────────────────────────────────────────────────

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<TvDevice?>(null)
    val connectedDevice: StateFlow<TvDevice?> = _connectedDevice.asStateFlow()

    private val _installedApps = MutableStateFlow<List<TvAppUiModel>>(emptyList())
    val installedApps: StateFlow<List<TvAppUiModel>> = _installedApps.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    val autoReconnect: Flow<Boolean> = prefs.autoReconnect

    private val _lgPairingKey = MutableStateFlow<String?>(null)

    // Internal controller (nullable when disconnected)
    private var controller: TvController? = null
    private var discoveryJob: Job? = null

    // ── Init: auto-reconnect on launch ────────────────────────────────────────
    init {
        viewModelScope.launch {
            val shouldReconnect = prefs.getAutoReconnectOnce()
            if (shouldReconnect) {
                val lastDevice = prefs.getLastDeviceOnce()
                if (lastDevice != null) {
                    Log.d(TAG, "Auto-reconnecting to ${lastDevice.name}")
                    connectTo(lastDevice)
                }
            }
        }
    }

    // ── Device connection ─────────────────────────────────────────────────────

    fun connectTo(device: TvDevice) {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Connecting
            _connectedDevice.value  = device

            controller?.disconnect()
            controller = buildController(device)

            val success = runCatching { controller!!.connect() }.getOrElse {
                Log.e(TAG, "Connection error: ${it.message}")
                false
            }

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
                prefs.saveLastDevice(device)
                loadInstalledApps()   // auto-load apps after connect
            } else {
                _connectionStatus.value = ConnectionStatus.Offline
                controller = null
            }
        }
    }

    fun disconnect() {
        controller?.disconnect()
        controller = null
        _connectionStatus.value = ConnectionStatus.Disconnected
        _connectedDevice.value  = null
    }

    private fun buildController(device: TvDevice): TvController = when (device.brand) {
        TvBrand.SAMSUNG -> SamsungTvController(device)
        TvBrand.LG      -> LgWebOsController(
            device           = device,
            savedPairingKey  = _lgPairingKey.value,
            onPairingKeyReceived = { key ->
                _lgPairingKey.value = key
                viewModelScope.launch { prefs.saveLastDevice(device) }
            }
        )
        TvBrand.ANDROID -> AndroidTvController(device)
        TvBrand.UNKNOWN -> SamsungTvController(device)
    }

    // ── Remote actions ────────────────────────────────────────────────────────

    fun sendKey(key: TvKey) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.sendKey(key) }
                .onFailure { Log.e(TAG, "sendKey failed: ${it.message}") }
        }
    }

    fun sendText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.sendText(text) }
                .onFailure { Log.e(TAG, "sendText failed: ${it.message}") }
        }
    }

    fun moveMouse(dx: Float, dy: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.moveMouse(dx, dy) }
                .onFailure { Log.w(TAG, "moveMouse: ${it.message}") }
        }
    }

    fun tapMouse() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.tapMouse() }
                .onFailure { Log.w(TAG, "tapMouse: ${it.message}") }
        }
    }

    // ── Volume / Channel ──────────────────────────────────────────────────────

    fun volumeUp()   = sendKey(TvKey.VOL_UP)
    fun volumeDown() = sendKey(TvKey.VOL_DOWN)
    fun mute()       = sendKey(TvKey.MUTE)
    fun channelUp()  = sendKey(TvKey.CH_UP)
    fun channelDown()= sendKey(TvKey.CH_DOWN)

    // ── Power / Wake-on-LAN ───────────────────────────────────────────────────

    fun power() = sendKey(TvKey.POWER)

    /** Sends a WoL magic packet to wake the TV from standby. */
    fun wakeTV() {
        val device = _connectedDevice.value ?: return
        if (device.mac.isBlank()) {
            Log.w(TAG, "No MAC address stored for ${device.name} – cannot send WoL")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                WakeOnLanUtil.sendMagicPacket(device.mac)
            }.onFailure {
                Log.e(TAG, "WoL failed: ${it.message}")
            }
        }
    }

    // ── App management ────────────────────────────────────────────────────────

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            val apps = runCatching { controller?.getInstalledApps() ?: emptyList() }
                .getOrElse { emptyList() }
                .map { it.toUiModel() }
            _installedApps.value = apps
            _isLoadingApps.value = false
        }
    }

    fun launchApp(appId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.launchApp(appId) }
                .onFailure { Log.e(TAG, "launchApp failed: ${it.message}") }
        }
    }

    // ── Device discovery ──────────────────────────────────────────────────────

    fun scanDevices() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { devices ->
                _discoveredDevices.value = devices
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
        // TODO: start / stop MediaProjection + RTSP/WebSocket stream to TV
        // This requires starting a Foreground Service that captures the screen
        // and streams it to the TV (Miracast / WebRTC / DLNA video sink).
        if (_isMirroring.value) {
            Log.d(TAG, "Mirroring started (stub)")
        } else {
            Log.d(TAG, "Mirroring stopped")
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoReconnect(enabled) }
    }

    // ── Macro execution ───────────────────────────────────────────────────────

    /** Runs a preset sequence of keys (e.g. "Movie Night" macro). */
    fun runMacro(keys: List<TvKey>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (key in keys) {
                runCatching { controller?.sendKey(key) }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    override fun onCleared() {
        controller?.disconnect()
        stopScan()
        super.onCleared()
    }
}

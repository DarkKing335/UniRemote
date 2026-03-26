package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.viewmodel.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles the connection lifecycle, protocol initialization, and command dispatching
 * to TvControllers, reducing the RemoteViewModel God Object anti-pattern.
 */
class DeviceConnectionManager(
    private val savedLgPairingKey: String?,
    private val onLgPairingKeyReceived: (String) -> Unit
) {
    private val TAG = "DeviceConnManager"
    private val CONNECT_TIMEOUT_MS = 4_000L

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<TvDevice?>(null)
    val connectedDevice: StateFlow<TvDevice?> = _connectedDevice.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var controller: TvController? = null
    private var googleTvPairingCtrl: GoogleTvController? = null
    private val connectMutex = Mutex()

    suspend fun tryConnectSilently(device: TvDevice): Boolean {
        return runCatching {
            controller?.disconnect()
            controller = buildController(device)
            controller!!.connect()
        }.getOrElse {
            Log.w(TAG, "tryConnect error: ${it.message}")
            false
        }
    }

    suspend fun connectTo(device: TvDevice): Boolean {
        return connectMutex.withLock {
            _connectionStatus.value = ConnectionStatus.Connecting
            _connectedDevice.value = device

            controller?.disconnect()
            controller = buildController(device)

            val success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runCatching { controller!!.connect() }.getOrElse { false }
            } ?: false

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
            } else {
                _connectionStatus.value = ConnectionStatus.Error("Cannot reach ${device.name}. Check that the TV is on and reachable.")
                controller = null
                _connectedDevice.value = null
            }
            success
        }
    }

    suspend fun disconnect() {
        connectMutex.withLock {
            controller?.disconnect()
            controller = null
            _connectionStatus.value = ConnectionStatus.Disconnected
            _connectedDevice.value = null
        }
    }

    fun setDisconnected() {
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    suspend fun sendKey(key: TvKey) {
        withContext(Dispatchers.IO) { controller?.sendKey(key) }
    }

    suspend fun sendText(text: String) {
        withContext(Dispatchers.IO) { controller?.sendText(text) }
    }

    suspend fun moveMouse(dx: Float, dy: Float) {
        withContext(Dispatchers.IO) { controller?.moveMouse(dx, dy) }
    }

    suspend fun tapMouse() {
        withContext(Dispatchers.IO) { controller?.tapMouse() }
    }

    suspend fun getInstalledApps(): List<TvApp> {
        return withContext(Dispatchers.IO) {
            controller?.getInstalledApps() ?: emptyList()
        }
    }

    suspend fun launchApp(appId: String) {
        withContext(Dispatchers.IO) { controller?.launchApp(appId) }
    }

    // ── Google TV Pairing ──────────────────────────────────────────────────────
    suspend fun startGoogleTvPairing(device: TvDevice): Boolean {
        _pairingState.value = PairingState.IDLE
        val ctrl = GoogleTvController(device) { state -> _pairingState.value = state }
        googleTvPairingCtrl = ctrl
        return ctrl.pair()
    }

    suspend fun submitPairingPin(pin: String) {
        googleTvPairingCtrl?.submitPin(pin)
    }

    fun cancelPairing() {
        _pairingState.value = PairingState.IDLE
        googleTvPairingCtrl = null
    }

    // ── Internals ─────────────────────────────────────────────────────────────
    private fun buildController(device: TvDevice): TvController = when (device.brand) {
        TvBrand.SAMSUNG   -> SamsungTvController(device)
        TvBrand.LG        -> LgWebOsController(
            device               = device,
            savedPairingKey      = savedLgPairingKey,
            onPairingKeyReceived = { onLgPairingKeyReceived(it) }
        )
        TvBrand.SONY      -> AndroidTvController(device)
        TvBrand.ANDROID   -> AndroidTvController(device)
        TvBrand.GOOGLE_TV -> GoogleTvController(device) { state -> _pairingState.value = state }
        TvBrand.ROKU      -> RokuController(device)
        TvBrand.UNKNOWN   -> SamsungTvController(device)
    }

    fun onCleared() {
        controller?.disconnect()
        googleTvPairingCtrl = null
    }
}

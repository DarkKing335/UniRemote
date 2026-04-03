package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.domain.ConnectionStatus
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
class DeviceConnectionManager {
    var onTokenReceived: ((String) -> Unit)? = null
    private val TAG = "DeviceConnManager"
    private val CONNECT_TIMEOUT_MS = 10_000L   // raised from 4s — some TVs are slow to respond

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<TvDevice?>(null)
    val connectedDevice: StateFlow<TvDevice?> = _connectedDevice.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var controller: TvController? = null
    private var googleTvPairingCtrl: GoogleTvController? = null
    private val connectMutex = Mutex()

    // ── Public helpers ─────────────────────────────────────────────────────────

    /**
     * Returns true if [device] requires going through the GoogleTV pairing handshake
     * before a normal control connection can be established.
     * Encapsulates this business rule so the UI layer doesn't need to know brand logic.
     */
    fun requiresPairing(device: TvDevice): Boolean = device.brand in setOf(
        TvBrand.GOOGLE_TV, TvBrand.ANDROID, TvBrand.SONY, TvBrand.XIAOMI
    )

    /**
     * Tries to connect to [device] silently (no status/UI update on failure).
     * On success, updates [_connectionStatus] and [_connectedDevice] so the UI
     * reflects the connected state immediately after auto-connect.
     */
    suspend fun tryConnectSilently(device: TvDevice): Boolean {
        return runCatching {
            controller?.disconnect()
            // Lazy: create and try each controller one at a time.
            // Unused controllers are never instantiated, so their SSL sockets/scopes are never opened.
            for (factory in controllerFactories(device)) {
                val ctrl = factory()
                val success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching { ctrl.connect() }.getOrElse { false }
                } ?: false
                if (success) {
                    controller = ctrl
                    _connectedDevice.value = device
                    _connectionStatus.value = ConnectionStatus.Connected
                    return@runCatching true
                }
                ctrl.disconnect()
            }
            false
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

            var success = false
            for (factory in controllerFactories(device)) {
                val ctrl = factory()
                success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching { ctrl.connect() }.getOrElse { false }
                } ?: false

                if (success) {
                    controller = ctrl
                    break
                }
                ctrl.disconnect()  // dispose the failed controller immediately
            }

            if (success) {
                _connectionStatus.value = ConnectionStatus.Connected
            } else {
                _connectionStatus.value = ConnectionStatus.Error(
                    "Không thể kết nối với ${device.name}. Kiểm tra TV đang bật và cùng mạng Wi-Fi."
                )
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

    /**
     * Returns a list of *factory lambdas* — one per protocol to try, in priority order.
     * Using lambdas (lazy evaluation) prevents eagerly creating all controllers upfront.
     * Non-selected controllers are never instantiated, so their SSL sockets / coroutine
     * scopes are never opened (fixing the scope-leak in GOOGLE_TV / UNKNOWN chains).
     */
    private fun controllerFactories(device: TvDevice): List<() -> TvController> = when (device.brand) {
        TvBrand.SAMSUNG -> listOf(
            { SamsungTvController(device) { token -> onTokenReceived?.invoke(token) } }
        )
        TvBrand.LG -> listOf(
            { LgWebOsController(device) { token -> onTokenReceived?.invoke(token) } }
        )
        TvBrand.FIRE_TV -> listOf(
            { AndroidTvController(device) }
        )
        TvBrand.ROKU -> listOf(
            { RokuController(device) }
        )

        TvBrand.GOOGLE_TV,
        TvBrand.ANDROID,
        TvBrand.SONY,
        TvBrand.XIAOMI -> listOf(
            { GoogleTvController(device) { state -> _pairingState.value = state } },
            { AndroidTvController(device) }
        )

        TvBrand.UNKNOWN -> listOf(
            { SamsungTvController(device) { token -> onTokenReceived?.invoke(token) } },
            { LgWebOsController(device) { token -> onTokenReceived?.invoke(token) } },
            { GoogleTvController(device) { state -> _pairingState.value = state } }
        )
    }

    fun onCleared() {
        controller?.disconnect()
        googleTvPairingCtrl = null
    }
}

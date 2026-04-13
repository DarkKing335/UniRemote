package com.example.uniremote.network

import android.util.Log
import com.example.uniremote.BuildConfig
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.domain.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
    private val SILENT_CONNECT_TIMEOUT_MS = 5_000L  // shorter for background auto-connect

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<TvDevice?>(null)
    val connectedDevice: StateFlow<TvDevice?> = _connectedDevice.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    /**
     * True when the active controller is [AndroidTvController] (ADB fallback).
     * The ViewModel observes this to warn users that shell commands may fail
     * if the TV has not approved the RSA fingerprint via its on-screen dialog.
     */
    private val _isAdbFallbackMode = MutableStateFlow(false)
    val isAdbFallbackMode: StateFlow<Boolean> = _isAdbFallbackMode.asStateFlow()

    private var controller: TvController? = null
    private var googleTvPairingCtrl: GoogleTvController? = null
    private val connectMutex = Mutex()

    // ── Public helpers ─────────────────────────────────────────────────────────

    /**
     * Returns true if [device] requires going through the GoogleTV pairing handshake
     * before a normal control connection can be established.
     * Returns false once [device.isPaired] is true — the pairing was already done and
     * the RSA key stored in Android KeyStore is trusted by the TV.
     */
    fun requiresPairing(device: TvDevice): Boolean = device.brand in setOf(
        TvBrand.GOOGLE_TV, TvBrand.ANDROID, TvBrand.SONY, TvBrand.XIAOMI
    ) && !device.isPaired

    /**
     * Tries to connect to [device] silently (no status/UI update on failure).
     * On success, updates [_connectionStatus] and [_connectedDevice] so the UI
     * reflects the connected state immediately after auto-connect.
     */
    suspend fun tryConnectSilently(device: TvDevice): Boolean {
        return runCatching {
            controller?.disconnect()
            // Use a shorter timeout for silent background attempts — we do not want
            // the startup auto-connect to block the UI for 10s per controller per device.
            for (factory in controllerFactories(device)) {
                val ctrl = factory()
                val success = withTimeoutOrNull(SILENT_CONNECT_TIMEOUT_MS) {
                    runCatching { ctrl.connect() }.getOrElse { false }
                } ?: false
                if (success) {
                    controller = ctrl
                    _connectedDevice.value = device
                    _connectionStatus.value = ConnectionStatus.Connected
                    _isAdbFallbackMode.value = ctrl is AndroidTvController
                    Log.i(TAG, "Auto-connected to ${device.name} via ${ctrl::class.simpleName}")
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
            _isAdbFallbackMode.value = false  // reset before each new connection attempt

            controller?.disconnect()

            var success = false
            var chosenCtrl: TvController? = null
            for (factory in controllerFactories(device)) {
                val ctrl = factory()
                success = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching { ctrl.connect() }.getOrElse { false }
                } ?: false

                if (success) {
                    chosenCtrl = ctrl
                    break
                }
                ctrl.disconnect()  // dispose the failed controller immediately
            }

            if (success && chosenCtrl != null) {
                controller = chosenCtrl
                // Detect ADB fallback so the ViewModel can warn the user
                _isAdbFallbackMode.value = chosenCtrl is AndroidTvController
                _connectionStatus.value = ConnectionStatus.Connected
            } else {
                val compatibilityBlocked = isCompatibilityBlocked(device)
                _connectionStatus.value = ConnectionStatus.Error(
                    if (compatibilityBlocked) {
                        "Ket noi bi chan boi che do bao mat production (insecure protocol disabled)."
                    } else {
                        "Khong the ket noi voi ${device.name}. Kiem tra TV dang bat va cung mang Wi-Fi."
                    }
                )
                controller = null
                _connectedDevice.value = null
                _isAdbFallbackMode.value = false
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
        withContext(Dispatchers.IO) { requireController().sendKey(key) }
    }

    suspend fun sendText(text: String) {
        withContext(Dispatchers.IO) { requireController().sendText(text) }
    }

    suspend fun moveMouse(dx: Float, dy: Float) {
        withContext(Dispatchers.IO) { requireController().moveMouse(dx, dy) }
    }

    suspend fun tapMouse() {
        withContext(Dispatchers.IO) { requireController().tapMouse() }
    }

    suspend fun getInstalledApps(): List<TvApp> {
        return withContext(Dispatchers.IO) {
            val primaryController = requireController()
            val primary = primaryController.getInstalledApps()
            if (primary.isNotEmpty()) return@withContext primary

            // Primary controller returned nothing (e.g. GoogleTvController always returns []).
            // Try fetching via ADB — the only protocol that can list installed packages.
            val device = _connectedDevice.value ?: return@withContext emptyList()
            val adb = AndroidTvController(device)
            val result = runCatching {
                val ok = withTimeoutOrNull(8_000L) { adb.connect() } ?: false
                if (ok) adb.getInstalledApps() else emptyList()
            }.getOrElse { emptyList() }
            runCatching { adb.disconnect() }
            result
        }
    }

    suspend fun launchApp(appId: String) {
        withContext(Dispatchers.IO) {
            val primaryController = requireController()
            val primaryIsGoogleTv = primaryController is GoogleTvController
            val primaryResult = runCatching { primaryController.launchApp(appId) }
            if (primaryResult.isSuccess && !primaryIsGoogleTv) return@withContext

            val primaryError = primaryResult.exceptionOrNull()
            val device = _connectedDevice.value
            val isAndroidFamily = device?.brand in setOf(
                TvBrand.GOOGLE_TV,
                TvBrand.ANDROID,
                TvBrand.XIAOMI,
                TvBrand.FIRE_TV,
                TvBrand.SONY
            )

            // Fallback: Google TV Remote protocol cannot launch apps directly.
            // Use ADB shell launch when available.
            if (device != null && isAndroidFamily) {
                val adb = AndroidTvController(device)
                val launchedByAdb = runCatching {
                    val ok = withTimeoutOrNull(8_000L) { adb.connect() } ?: false
                    if (!ok) return@runCatching false
                    adb.launchApp(appId)
                    true
                }.getOrDefault(false)
                runCatching { adb.disconnect() }
                if (launchedByAdb) return@withContext
            }

            if (primaryResult.isSuccess) return@withContext

            throw (primaryError ?: IllegalStateException("Không thể mở ứng dụng $appId"))
        }
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
        TvBrand.XIAOMI -> buildList {
            // 1st: Google TV Remote Protocol (port 6466) — native pairing/control
            add { GoogleTvController(device) { state -> _pairingState.value = state } }
            // Last resort: ADB — connects if TV has Developer Options + ADB over network enabled
            add { AndroidTvController(device) }
        }

        TvBrand.SONY -> buildList {
            // Sony Bravia — two protocol generations, try in order:
            // 1st: Google TV Remote Protocol — Android TV models (2016+, KDL-43W800F etc.)
            add { GoogleTvController(device) { state -> _pairingState.value = state } }
            // 2nd: Sony IRCC-IP — pre-Android TV models (older KDL, EX, HX series 2012–2015)
            add {
                SonyBraviaController(
                    device          = device,
                    pinChannel      = Channel(Channel.RENDEZVOUS),
                    onPairingState  = { state -> _pairingState.value = state },
                    onTokenReceived = { token -> onTokenReceived?.invoke(token) }
                )
            }
            // Last resort: ADB
            add { AndroidTvController(device) }
        }

        TvBrand.UNKNOWN -> listOf(
            { SamsungTvController(device) { token -> onTokenReceived?.invoke(token) } },
            { LgWebOsController(device) { token -> onTokenReceived?.invoke(token) } },
            { GoogleTvController(device) { state -> _pairingState.value = state } },
            // Last resort: ADB
            { AndroidTvController(device) }
        )
    }

    fun onCleared() {
        controller?.disconnect()
        googleTvPairingCtrl = null
    }

    private fun requireController(): TvController {
        return controller ?: throw IllegalStateException("No active TV connection")
    }

    private fun isCompatibilityBlocked(device: TvDevice): Boolean {
        if (BuildConfig.ENABLE_INSECURE_DEVICE_PROTOCOLS) return false
        return when (device.brand) {
            TvBrand.LG, TvBrand.ROKU -> true
            TvBrand.SAMSUNG, TvBrand.UNKNOWN -> device.port != 8002
            TvBrand.SONY -> true
            else -> false
        }
    }
}

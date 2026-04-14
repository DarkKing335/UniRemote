package com.example.uniremote.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastState
import com.example.uniremote.cast.DefaultCastManager
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.domain.AutoConnectUseCase
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.network.DeviceConnectionManager
import com.example.uniremote.network.PairingState
import com.example.uniremote.network.RemoteControlDiscovery
import com.example.uniremote.network.SoftwareTlsKey
import com.example.uniremote.network.TvKey
import com.example.uniremote.util.WifiUtil
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STARTUP_DELAY_MS = 1_000L
private const val TAG = "RemoteViewModel"

class RemoteViewModel @JvmOverloads constructor(
    application: Application,
    private val prefs: AppPreferences = AppPreferences(application),
    private val repo: DeviceRepository = DeviceRepository(application, prefs),
    private val discovery: RemoteControlDiscovery = RemoteControlDiscovery(application),
    private val connectionManager: DeviceConnectionManager = DeviceConnectionManager(application),
    private val castRepository: CastRepository = CastRepository(application),
    private val castManager: DefaultCastManager = DefaultCastManager(application, castRepository),
    private val autoConnectUseCase: AutoConnectUseCase = AutoConnectUseCase(repo, connectionManager),
    private val initializeOnStartup: Boolean = true
) : AndroidViewModel(application) {

    val knownDevices: StateFlow<List<TvDevice>> = repo.knownDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _isTextInputActive = MutableStateFlow(false)
    val isTextInputActive: StateFlow<Boolean> = _isTextInputActive.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    val autoReconnect: Flow<Boolean> = prefs.autoReconnect

    val userMacros: StateFlow<List<UserMacro>> = prefs.userMacros
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val connectionViewModel = ConnectionViewModel(
        application = application,
        scope = viewModelScope,
        repo = repo,
        connectionManager = connectionManager,
        knownDevices = knownDevices,
        isTextInputActive = { _isTextInputActive.value },
        emitToast = { _toastMessage.tryEmit(it) },
        reportFailure = { action, throwable, fallback -> reportFailure(action, throwable, fallback) }
    )

    private val castViewModel = CastViewModel(
        application = application,
        scope = viewModelScope,
        prefs = prefs,
        castRepository = castRepository,
        castManager = castManager,
        connectedDevice = connectionViewModel.connectedDevice,
        emitToast = { _toastMessage.tryEmit(it) },
        reportFailure = { action, throwable, fallback -> reportFailure(action, throwable, fallback) }
    )

    private val discoveryCoordinator = DiscoveryCoordinator(
        scope = viewModelScope,
        discovery = discovery,
        repo = repo
    )

    val connectionStatus: StateFlow<ConnectionStatus> = connectionViewModel.connectionStatus
    val connectedDevice: StateFlow<TvDevice?> = connectionViewModel.connectedDevice
    val pairingState: StateFlow<PairingState> = connectionViewModel.pairingState

    val installedApps: StateFlow<List<com.example.uniremote.network.TvAppUiModel>> =
        connectionViewModel.installedApps
    val isLoadingApps: StateFlow<Boolean> = connectionViewModel.isLoadingApps

    val discoveredDevices: StateFlow<List<TvDevice>> = discoveryCoordinator.discoveredDevices

    val isCastFeatureAvailable: StateFlow<Boolean> = castViewModel.isCastFeatureAvailable
    val castRenderers = castViewModel.castRenderers
    val castState: StateFlow<CastState> = castViewModel.castState
    val castPlaybackInfo: StateFlow<CastPlaybackInfo> = castViewModel.castPlaybackInfo
    val mirrorStreamUrl: StateFlow<String?> = castViewModel.mirrorStreamUrl
    val mirrorAuthHint: StateFlow<String?> = castViewModel.mirrorAuthHint
    val mirrorTlsFingerprint: StateFlow<String?> = castViewModel.mirrorTlsFingerprint
    val isMirroring: StateFlow<Boolean> = castViewModel.isMirroring
    val selectedCastRendererUdn: StateFlow<String?> = castViewModel.selectedCastRendererUdn
    val selectedCastRendererName: StateFlow<String?> = castViewModel.selectedCastRendererName

    private var macroJob: Job? = null
    private var startupJob: Job? = null
    private var ssidJob: Job? = null

    private val _isMacroRunning = MutableStateFlow(false)
    val isMacroRunning: StateFlow<Boolean> = _isMacroRunning.asStateFlow()

    private val _currentMacroId = MutableStateFlow<String?>(null)
    val currentMacroId: StateFlow<String?> = _currentMacroId.asStateFlow()

    init {
        if (initializeOnStartup) {
            connectionViewModel.bindTokenPersistence()
            castViewModel.ensureCastDiscoveryStarted()

            startupJob = viewModelScope.launch(Dispatchers.IO) {
                SoftwareTlsKey.init(application)
                prefs.migrateKnownDeviceTokensToSecureStore()
                prefs.seedDefaultMacros()
                if (prefs.getAutoReconnectOnce()) {
                    delay(STARTUP_DELAY_MS)
                    autoConnect()
                }
            }

            ssidJob = viewModelScope.launch {
                WifiUtil.observeCurrentSsid(application).collect { ssid ->
                    _currentSsid.value = ssid
                }
            }
        }
    }

    private suspend fun autoConnect() {
        val connected = autoConnectUseCase()
        if (connected) {
            connectionViewModel.loadInstalledApps()
        }
    }

    fun setTextInputActive(active: Boolean) {
        _isTextInputActive.value = active
    }

    fun connectOrPair(device: TvDevice) = connectionViewModel.connectOrPair(device)

    fun connectTo(device: TvDevice) = connectionViewModel.connectTo(device)

    fun connectToManualRokuIp(ip: String, name: String = "Roku (Manual IP)") =
        connectionViewModel.connectToManualRokuIp(ip, name)

    fun startGoogleTvPairing(device: TvDevice) = connectionViewModel.startGoogleTvPairing(device)

    fun submitPairingPin(pin: String) = connectionViewModel.submitPairingPin(pin)

    fun cancelPairing() = connectionViewModel.cancelPairing()

    fun disconnect() {
        macroJob?.cancel()
        macroJob = null
        connectionViewModel.disconnect()
    }

    fun forgetDevice(deviceId: String) = connectionViewModel.forgetDevice(deviceId)

    fun sendKey(key: TvKey) = connectionViewModel.sendKey(key)

    fun triggerSearchAction() = connectionViewModel.triggerSearchAction()

    fun triggerSourceAction() = connectionViewModel.triggerSourceAction()

    fun triggerGuideAction() = connectionViewModel.triggerGuideAction()

    fun triggerActionMenu() = connectionViewModel.triggerActionMenu()

    fun triggerInfoAction() = connectionViewModel.triggerInfoAction()

    fun triggerDigitalAnalogAction() = connectionViewModel.triggerDigitalAnalogAction()

    fun sendText(text: String) = connectionViewModel.sendText(text)

    fun sendTextAndEnter(text: String) = connectionViewModel.sendTextAndEnter(text)

    fun moveMouse(dx: Float, dy: Float) = connectionViewModel.moveMouse(dx, dy)

    fun tapMouse() = connectionViewModel.tapMouse()

    fun volumeUp() = sendKey(TvKey.VOL_UP)

    fun volumeDown() = sendKey(TvKey.VOL_DOWN)

    fun mute() = sendKey(TvKey.MUTE)

    fun channelUp() = sendKey(TvKey.CH_UP)

    fun channelDown() = sendKey(TvKey.CH_DOWN)

    fun power() = sendKey(TvKey.POWER)

    fun wakeTV() = connectionViewModel.wakeTV()

    fun loadInstalledApps() = connectionViewModel.loadInstalledApps()

    fun launchApp(appId: String) = connectionViewModel.launchApp(appId)

    internal suspend fun launchAppInternal(appId: String) = connectionViewModel.launchAppInternal(appId)

    fun scanDevices() = discoveryCoordinator.scanDevices()

    fun stopScan() = discoveryCoordinator.stopScan()

    fun requestCastFeatureInfo() = castViewModel.requestCastFeatureInfo()

    fun ensureCastDiscoveryStarted() = castViewModel.ensureCastDiscoveryStarted()

    fun bindCastService() = castViewModel.bindCastService()

    fun unbindCastService() = castViewModel.unbindCastService()

    fun refreshCastDevices() = castViewModel.refreshCastDevices()

    fun selectCastRenderer(rendererUdn: String, rendererName: String, persist: Boolean = true) =
        castViewModel.selectCastRenderer(rendererUdn, rendererName, persist)

    fun findDlnaFallbackRenderer(preferredRendererName: String?): DlnaRenderer? =
        castViewModel.findDlnaFallbackRenderer(preferredRendererName)

    fun castMedia(
        uri: Uri,
        mimeType: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) = castViewModel.castMedia(uri, mimeType, title, rendererUdn, rendererName)

    fun stopCast() = castViewModel.stopCast()

    fun playCast() = castViewModel.playCast()

    fun pauseCast() = castViewModel.pauseCast()

    fun seekCastBy(deltaMs: Long) = castViewModel.seekCastBy(deltaMs)

    fun seekCastTo(positionMs: Long) = castViewModel.seekCastTo(positionMs)

    fun changeCastVolumeBy(step: Int) = castViewModel.changeCastVolumeBy(step)

    fun setCastVolume(level: Int) = castViewModel.setCastVolume(level)

    fun toggleCastMute() = castViewModel.toggleCastMute()

    fun startMirroring(
        resultCode: Int,
        data: Intent,
        rendererUdn: String? = null,
        rendererName: String? = null
    ) = castViewModel.startMirroring(resultCode, data, rendererUdn, rendererName)

    fun getMirrorAuthorizationHeaderForManualShare(): String? =
        castViewModel.getMirrorAuthorizationHeaderForManualShare()

    fun onMirroringPermissionDenied() = castViewModel.onMirroringPermissionDenied()

    fun stopMirroring() = castViewModel.stopMirroring()

    fun toggleMirroring() = castViewModel.toggleMirroring()

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
                    try {
                        connectionManager.sendKey(key)
                    } catch (e: Exception) {
                        reportFailure("runMacro:$macroId", e, "Macro bi gian doan do mat ket noi")
                        break
                    }
                    delay(200)
                }
            } finally {
                _isMacroRunning.value = false
                _currentMacroId.value = null
            }
        }
    }

    fun saveMacro(macro: UserMacro) = viewModelScope.launch {
        runCatching { prefs.saveMacro(macro) }
            .onFailure { reportFailure("saveMacro", it, "Khong the luu macro") }
    }

    fun deleteMacro(macroId: String) = viewModelScope.launch {
        runCatching { prefs.deleteMacro(macroId) }
            .onFailure { reportFailure("deleteMacro", it, "Khong the xoa macro") }
    }

    fun runUserMacro(macroId: String) {
        userMacros.value.firstOrNull { it.id == macroId }?.let { runMacro(it.keys, macroId) }
    }

    override fun onCleared() {
        macroJob?.cancel()
        startupJob?.cancel()
        ssidJob?.cancel()
        connectionViewModel.cancelPendingWork()
        connectionManager.onCleared()
        discoveryCoordinator.stopScan()
        castViewModel.onCleared()
        super.onCleared()
    }

    private fun reportFailure(action: String, throwable: Throwable, fallbackMessage: String) {
        val userMessage = throwable.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
        _toastMessage.tryEmit(userMessage)
        logTelemetry(action, throwable)
    }

    private fun logTelemetry(action: String, throwable: Throwable) {
        runCatching {
            Log.e(
                TAG,
                "action=$action failed: ${throwable::class.java.simpleName}: ${throwable.message}",
                throwable
            )
        }
    }
}

package com.example.uniremote.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uniremote.cast.DefaultCastManager
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastState
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.LastCastRenderer
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
import kotlin.math.abs
import java.util.Locale
import com.uniremote.dlna.dlna.DlnaRenderer

private const val STARTUP_DELAY_MS = 1_000L
private const val CAST_REFRESH_INTERVAL_MS = 20_000L
private const val TAG = "RemoteViewModel"

private data class CoreAppSpec(
    val key: String,
    val displayName: String,
    val matchTerms: List<String>,
    val packageCandidates: List<String>
)

private val CORE_APPS = listOf(
    CoreAppSpec(
        key = "youtube",
        displayName = "YouTube",
        matchTerms = listOf("youtube", "you tube"),
        packageCandidates = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvkids",
            "com.google.android.youtube"
        )
    ),
    CoreAppSpec(
        key = "netflix",
        displayName = "Netflix",
        matchTerms = listOf("netflix"),
        packageCandidates = listOf(
            "com.netflix.ninja",
            "com.netflix.mediaclient"
        )
    ),
    CoreAppSpec(
        key = "primevideo",
        displayName = "Prime Video",
        matchTerms = listOf("prime video", "primevideo", "amazon prime"),
        packageCandidates = listOf(
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.avod.thirdpartyclient"
        )
    ),
    CoreAppSpec(
        key = "spotify",
        displayName = "Spotify",
        matchTerms = listOf("spotify"),
        packageCandidates = listOf(
            "com.spotify.tv.android",
            "com.spotify.music"
        )
    )
)

class RemoteViewModel @JvmOverloads constructor(
    application: Application,
    private val prefs: AppPreferences = AppPreferences(application),
    private val repo: DeviceRepository = DeviceRepository(application, prefs),
    private val discovery: DeviceDiscovery = DeviceDiscovery(application),
    private val connectionManager: DeviceConnectionManager = DeviceConnectionManager(),
    private val castRepository: CastRepository = CastRepository(application),
    private val castManager: DefaultCastManager = DefaultCastManager(application, castRepository),
    private val autoConnectUseCase: AutoConnectUseCase = AutoConnectUseCase(repo, connectionManager),
    private val initializeOnStartup: Boolean = true
) : AndroidViewModel(application) {

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

    // Cast — now fully implemented via DLNA + MediaProjection.
    private val _isCastFeatureAvailable = MutableStateFlow(true)
    val isCastFeatureAvailable: StateFlow<Boolean> = _isCastFeatureAvailable.asStateFlow()

    /** DLNA renderers discovered on the local network. */
    val castRenderers = castManager.castRenderers

    /** Current DLNA casting session state (Idle / Discovering / Casting / Error). */
    val castState: StateFlow<CastState> = castManager.castState

    /** Live DLNA telemetry (position/duration/volume/mute) for cast control UI. */
    val castPlaybackInfo: StateFlow<CastPlaybackInfo> = castManager.castPlaybackInfo

    /** Public stream endpoint (no token in URL), null when not mirroring. */
    val mirrorStreamUrl: StateFlow<String?> = castManager.mirrorStreamUrl

    /** Masked auth token hint (e.g., abcd…wxyz) for manual client configuration. */
    val mirrorAuthHint: StateFlow<String?> = castManager.mirrorAuthHint

    /** TLS certificate fingerprint for trust verification on clients. */
    val mirrorTlsFingerprint: StateFlow<String?> = castManager.mirrorTlsFingerprint

    val isMirroring: StateFlow<Boolean> = castManager.isMirroring

    private val _selectedCastRendererUdn = MutableStateFlow<String?>(null)
    val selectedCastRendererUdn: StateFlow<String?> = _selectedCastRendererUdn.asStateFlow()

    private val _selectedCastRendererName = MutableStateFlow<String?>(null)
    val selectedCastRendererName: StateFlow<String?> = _selectedCastRendererName.asStateFlow()

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
    private var ssidJob: Job? = null
    private var castRefreshJob: Job? = null
    private var castDiscoveryStarted = false
    private var restoredLastCastRenderer = false

    // Drag-to-DPad fallback accumulators for TVs that do not support mouse pointer protocol.
    private var mouseFallbackAccumX = 0f
    private var mouseFallbackAccumY = 0f
    private val mouseFallbackStepPx = 28f

    init {
        if (initializeOnStartup) {
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

            ensureCastDiscoveryStarted()
            observeCastRendererAutoSelection()

            connectJob = viewModelScope.launch(Dispatchers.IO) {
                // Init the software RSA key FIRST, on a background thread.
                // RSA-2048 generation can take 500ms-3s; calling it on the main thread causes ANR.
                // This must complete before autoConnect() creates a GoogleTvController.
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
            loadInstalledApps()
        } else {
            // Don't show a toast here — it's confusing when the TV IS on but the
            // TLS handshake is simply slow. The user can see the Disconnected state
            // in the Settings screen and tap Connect manually.
        }
    }

    /**
     * Smart connect:
     * - For Google TV family (Sony/Android TV/Google TV/Xiaomi): runs the PIN pairing flow
     *   if the device has never been successfully paired (isPaired == false).
     *   On subsequent launches the stored isPaired flag lets us skip the PIN entirely.
     * - For all other brands (Samsung/LG/Roku/Fire TV): connects directly.
     *
     * Always cancels any in-progress connection/pairing job first to avoid race conditions
     * where auto-connect via ADB would be running concurrently with a new pairing attempt.
     */
    fun connectOrPair(device: TvDevice) {
        // Cancel any ongoing auto-connect or previous connect attempt first.
        // This is critical: without it, an ADB auto-connect running in the background
        // can pre-empt a new pairing attempt and set connectionStatus = Connected
        // before the PIN dialog is shown.
        connectJob?.cancel()
        connectJob = null

        val savedDevice = knownDevices.value.firstOrNull { it.id == device.id }
        // Always prefer the persisted device — it carries the authoritative isPaired flag.
        // A freshly-scanned device object always has isPaired=false even if we paired before.
        val effectiveDevice = savedDevice ?: device
        if (connectionManager.requiresPairing(effectiveDevice) && !effectiveDevice.isPaired) {
            startGoogleTvPairing(effectiveDevice)
        } else {
            connectTo(effectiveDevice)
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
                _toastMessage.tryEmit("Không thể kết nối với ${device.name}. Vui lòng kiểm tra lại TV.")
            }
        }
    }

    fun startGoogleTvPairing(device: TvDevice) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val paired = connectionManager.startGoogleTvPairing(device)
            if (paired) {
                // Persist isPaired = true so future launches skip the PIN entirely.
                val pairedDevice = device.copy(isPaired = true)
                repo.saveDevice(pairedDevice)
                connectTo(pairedDevice)
            } else {
                // Pairing failed or was cancelled by the user.
                //
                // Check if the device was already paired in a PREVIOUS session —
                // i.e. isPaired was saved as true before, which means the RSA key
                // is still trusted by the TV and we can connect to the control port directly.
                val savedDevice = knownDevices.value.firstOrNull { it.id == device.id }
                if (savedDevice?.isPaired == true) {
                    // Key is still trusted — silent retry on control port
                    connectTo(savedDevice)
                } else {
                    // Pairing was genuinely cancelled/failed and the device has NEVER been
                    // successfully paired. Do NOT fall back to ADB here — that would allow
                    // the device to appear "connected" while bypassing the pairing requirement,
                    // which causes the PIN to never appear on subsequent connect attempts.
                    _toastMessage.tryEmit(
                        "Ghép đôi bị huỷ. Vào Settings → chọn TV → bấm Kết nối để thử ghép đôi lại."
                    )
                    // Reset so Settings screen shows device as Disconnected, not Connected
                    connectionManager.setDisconnected()
                }
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
        try {
            connectionManager.sendKey(key)
        } catch (e: Exception) {
            reportFailure("sendKey:$key", e, "Tín hiệu bị mất, chờ tivi phản hồi.")
        }
    }

    fun sendText(text: String) = viewModelScope.launch {
        try {
            connectionManager.sendText(text)
        } catch (e: Exception) {
            reportFailure("sendText", e, "Lỗi gửi phím cứng")
        }
    }

    fun sendTextAndEnter(text: String) = viewModelScope.launch {
        try {
            connectionManager.sendText(text)
            delay(300)
            connectionManager.sendKey(TvKey.OK)
        } catch (e: Exception) {
            reportFailure("sendTextAndEnter", e, "Lỗi gửi đoạn văn bản")
        }
    }

    fun moveMouse(dx: Float, dy: Float) = viewModelScope.launch {
        try {
            connectionManager.moveMouse(dx, dy)
        } catch (e: UnsupportedOperationException) {
            // Graceful fallback: map drag gestures to D-Pad navigation instead of
            // surfacing repeated unsupported-mouse errors while the user is swiping.
            runCatching { fallbackDirectionalFromDrag(dx, dy) }
                .onFailure { reportFailure("moveMouseFallback", it, "Không thể điều hướng") }
        } catch (e: Exception) {
            reportFailure("moveMouse", e, "Không thể điều khiển chuột")
        }
    }

    fun tapMouse() = viewModelScope.launch {
        try {
            connectionManager.tapMouse()
        } catch (e: UnsupportedOperationException) {
            if (isTextInputActive.value) {
                // In text-input mode, fallback OK causes accidental character insertion
                // on TV virtual keyboards (current highlighted key gets committed).
                return@launch
            }
            // Fallback for non-pointer protocols (Samsung/Google TV/Roku):
            // tap on touchpad should still behave like D-Pad select.
            runCatching { connectionManager.sendKey(TvKey.OK) }
                .onFailure { reportFailure("tapMouseFallback", it, "Không thể chọn mục") }
        } catch (e: Exception) {
            reportFailure("tapMouse", e, "Không thể click chuột")
        }
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
            try {
                val syncedApps = connectionManager.getInstalledApps()
                _installedApps.value = mergeCoreAppsWithSynced(syncedApps)
            } catch (e: Exception) {
                _installedApps.value = emptyList()
                reportFailure("loadInstalledApps", e, "Không thể tải danh sách ứng dụng")
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun launchApp(appId: String) = viewModelScope.launch {
        launchAppInternal(appId)
    }

    internal suspend fun launchAppInternal(appId: String) {
        if (appId.startsWith("core:")) {
            val coreKey = appId.substringAfter("core:")
            launchCoreApp(coreKey)
            return
        }

        try {
            connectionManager.launchApp(appId)
        } catch (e: Exception) {
            reportFailure("launchApp:$appId", e, "Không thể mở ứng dụng này trên TV")
        }
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
        // Feature is now live — this is a no-op kept for backward compat.
    }

    // ── Cast (DLNA Media) ─────────────────────────────────────────────────────

    /** Starts discovery once and keeps it alive so switching tabs does not reset renderer list. */
    fun ensureCastDiscoveryStarted() {
        if (castDiscoveryStarted) return
        castDiscoveryStarted = true
        castManager.bind()

        // Warm discovery as soon as app starts, then keep refreshes running in background.
        refreshCastDevicesInternal()
        castRefreshJob = viewModelScope.launch {
            while (true) {
                delay(CAST_REFRESH_INTERVAL_MS)
                refreshCastDevicesInternal()
            }
        }
    }

    /** Backward-compatible alias used by Cast screen. */
    fun bindCastService() = ensureCastDiscoveryStarted()

    /** Intentionally no-op: keep discovery alive across tab switches. */
    fun unbindCastService() = Unit

    /** Re-run UPnP search for DLNA renderers. Also tries unicast to connected TV's IP. */
    fun refreshCastDevices() {
        ensureCastDiscoveryStarted()
        refreshCastDevicesInternal()
    }

    private fun refreshCastDevicesInternal() {
        // Pass the connected TV's IP as unicast hint — bypasses multicast routing issues
        castManager.setHintDeviceIp(connectedDevice.value?.ip)
        castManager.discoverDevices()
    }

    fun selectCastRenderer(rendererUdn: String, rendererName: String, persist: Boolean = true) {
        val currentUdn = _selectedCastRendererUdn.value
        val currentName = _selectedCastRendererName.value
        if (currentUdn == rendererUdn && currentName == rendererName) {
            return
        }

        castManager.selectRenderer(rendererUdn, rendererName)
        _selectedCastRendererUdn.value = rendererUdn
        _selectedCastRendererName.value = rendererName

        if (persist) {
            viewModelScope.launch {
                prefs.saveLastCastRenderer(rendererUdn, rendererName)
            }
        }
    }

    /**
     * For Google Cast mirroring fallback: pick a DLNA renderer, preferring same-TV name match.
     */
    fun findDlnaFallbackRenderer(preferredRendererName: String?): DlnaRenderer? {
        val dlnaRenderers = castRenderers.value.filterNot { it.udn.startsWith("gcast:") }
        if (dlnaRenderers.isEmpty()) return null

        val preferred = preferredRendererName?.trim()?.lowercase(Locale.ROOT)
        if (preferred.isNullOrBlank()) return dlnaRenderers.first()

        return dlnaRenderers.firstOrNull { renderer ->
            val candidate = renderer.name.trim().lowercase(Locale.ROOT)
            candidate == preferred || candidate.contains(preferred) || preferred.contains(candidate)
        } ?: dlnaRenderers.first()
    }

    /**
     * Registers [uri] with the local HTTP media server and sends a DLNA
     * AVTransport SetURI + Play to the chosen renderer.
     */
    fun castMedia(
        uri: Uri,
        mimeType: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) {
        selectCastRenderer(rendererUdn, rendererName)

        val mediaUrl = uri.toString()
        if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
            runCatching {
                castManager.castMedia(
                    url = mediaUrl,
                    title = title,
                    mimeType = inferMimeTypeFromUrl(mediaUrl)
                )
            }.onFailure {
                reportFailure("castMediaUrl", it, "Không thể cast URL media tới TV")
            }
            return
        }

        val resolvedMimeType = mimeType.ifBlank {
            getApplication<Application>().contentResolver.getType(uri) ?: "video/*"
        }

        if (rendererUdn.startsWith("gcast:")) {
            runCatching {
                val mediaUrl = castRepository.buildLocalMediaUrl(
                    uri = uri,
                    mimeType = resolvedMimeType,
                    title = title
                ) ?: error("Cannot prepare local media URL for Chromecast")

                castManager.castMedia(
                    url = mediaUrl,
                    title = title,
                    mimeType = resolvedMimeType
                )
            }.onFailure {
                reportFailure("castLocalMediaGoogleCast", it, "Không thể cast file local lên Chromecast")
            }
            return
        }

        runCatching {
            castRepository.castMedia(
                uri = uri,
                mimeType = resolvedMimeType,
                title = title,
                rendererUdn = rendererUdn,
                rendererName = rendererName
            )
        }.onFailure {
            reportFailure("castLocalMedia", it, "Không thể cast file media từ điện thoại")
        }
    }

    /** Stop the active DLNA cast session. */
    fun stopCast() = castManager.stopCast()

    fun playCast() = castManager.play()

    fun pauseCast() = castManager.pause()

    fun seekCastBy(deltaMs: Long) {
        val current = castPlaybackInfo.value
        val upper = if (current.durationMs > 0L) current.durationMs else Long.MAX_VALUE
        val next = (current.positionMs + deltaMs).coerceIn(0L, upper)
        castManager.seek(next)
    }

    fun seekCastTo(positionMs: Long) = castManager.seek(positionMs)

    fun changeCastVolumeBy(step: Int) = castManager.changeVolumeBy(step)

    fun setCastVolume(level: Int) = castManager.setVolume(level)

    fun toggleCastMute() = castManager.toggleMute()

    // ── Screen Mirroring (MediaProjection → MediaCodec H.264) ─────────────────

    /**
     * Starts screen mirroring using the ActivityResult payload from MediaProjection permission flow.
     * Must be called from an ActivityResult callback.
     */
    fun startMirroring(
        resultCode: Int,
        data: Intent,
        rendererUdn: String? = null,
        rendererName: String? = null
    ) {
        runCatching {
            if (!rendererUdn.isNullOrBlank() && !rendererName.isNullOrBlank()) {
                selectCastRenderer(rendererUdn, rendererName)

                if (rendererUdn.startsWith("gcast:")) {
                    _toastMessage.tryEmit(
                        "Chromecast chưa tự phát được luồng Screen Mirroring (.h264). Dùng Cast Video/Image hoặc tính năng Cast màn hình hệ thống."
                    )
                }
            }
            castManager.configureMirroringProjection(resultCode, data)
            castManager.startMirroring()
        }.onFailure {
            reportFailure("startMirroring", it, "Không thể bắt đầu phản chiếu màn hình")
        }
    }

    fun getMirrorAuthorizationHeaderForManualShare(): String? =
        castManager.getMirrorAuthorizationHeaderForManualShare()

    fun onMirroringPermissionDenied() {
        castManager.stopMirroring()
        _toastMessage.tryEmit("Bạn cần cấp quyền thông báo để bắt đầu phản chiếu màn hình")
    }

    /** Stops the H.264 mirroring service. */
    fun stopMirroring() = castManager.stopMirroring()

    /** Toggle helper — start must be initiated from UI (requires Activity result). */
    fun toggleMirroring() {
        if (isMirroring.value) stopMirroring()
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
                    try {
                        connectionManager.sendKey(key)
                    } catch (e: Exception) {
                        reportFailure("runMacro:$macroId", e, "Macro bị gián đoạn do mất kết nối")
                        break
                    }
                    delay(200)
                }
            } finally {
                // Always clear both flags, even on cancellation or error
                _isMacroRunning.value = false
                _currentMacroId.value = null
            }
        }
    }

    fun saveMacro(macro: UserMacro) = viewModelScope.launch {
        runCatching { prefs.saveMacro(macro) }
            .onFailure { reportFailure("saveMacro", it, "Không thể lưu macro") }
    }

    fun deleteMacro(macroId: String) = viewModelScope.launch {
        runCatching { prefs.deleteMacro(macroId) }
            .onFailure { reportFailure("deleteMacro", it, "Không thể xóa macro") }
    }
    fun runUserMacro(macroId: String) {
        userMacros.value.firstOrNull { it.id == macroId }?.let { runMacro(it.keys, macroId) }
    }

    override fun onCleared() {
        macroJob?.cancel()
        connectJob?.cancel()
        ssidJob?.cancel()
        castRefreshJob?.cancel()
        connectionManager.onCleared()
        stopScan()
        castManager.release()
        super.onCleared()
    }

    private fun reportFailure(action: String, throwable: Throwable, fallbackMessage: String) {
        val userMessage = throwable.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
        _toastMessage.tryEmit(userMessage)
        logTelemetry(action, throwable)
    }

    private fun logTelemetry(action: String, throwable: Throwable) {
        Log.e(TAG, "action=$action failed: ${throwable::class.java.simpleName}: ${throwable.message}", throwable)
    }

    private fun inferMimeTypeFromUrl(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.endsWith(".h264") -> "video/avc"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    private suspend fun fallbackDirectionalFromDrag(dx: Float, dy: Float) {
        // When the user reverses direction, reset the opposite momentum so fallback
        // navigation follows finger intent immediately instead of lagging.
        if (dx > 0f && mouseFallbackAccumX < 0f) mouseFallbackAccumX = 0f
        if (dx < 0f && mouseFallbackAccumX > 0f) mouseFallbackAccumX = 0f
        if (dy > 0f && mouseFallbackAccumY < 0f) mouseFallbackAccumY = 0f
        if (dy < 0f && mouseFallbackAccumY > 0f) mouseFallbackAccumY = 0f

        mouseFallbackAccumX += dx
        mouseFallbackAccumY += dy

        while (abs(mouseFallbackAccumX) >= mouseFallbackStepPx || abs(mouseFallbackAccumY) >= mouseFallbackStepPx) {
            val emitHorizontal = abs(mouseFallbackAccumX) >= abs(mouseFallbackAccumY)
            val key = if (emitHorizontal) {
                if (mouseFallbackAccumX > 0f) TvKey.RIGHT else TvKey.LEFT
            } else {
                if (mouseFallbackAccumY > 0f) TvKey.DOWN else TvKey.UP
            }
            connectionManager.sendKey(key)

            if (emitHorizontal) {
                mouseFallbackAccumX += if (mouseFallbackAccumX > 0f) -mouseFallbackStepPx else mouseFallbackStepPx
            } else {
                mouseFallbackAccumY += if (mouseFallbackAccumY > 0f) -mouseFallbackStepPx else mouseFallbackStepPx
            }
        }
    }

    private fun mergeCoreAppsWithSynced(synced: List<TvApp>): List<TvAppUiModel> {
        val matchedSyncedIds = mutableSetOf<String>()
        val coreEntries = CORE_APPS.map { spec ->
            val matched = synced.firstOrNull { app ->
                val idLower = app.id.lowercase(Locale.ROOT)
                val nameLower = app.name.lowercase(Locale.ROOT)
                spec.matchTerms.any { term ->
                    val t = term.lowercase(Locale.ROOT)
                    idLower.contains(t) || nameLower.contains(t)
                }
            }
            if (matched != null) {
                matchedSyncedIds += matched.id
                matched.toUiModel()
            } else {
                // Synthetic core entry shown even if this sync pass didn't return it.
                TvAppUiModel(id = "core:${spec.key}", name = spec.displayName)
            }
        }

        val extras = synced
            .filterNot { it.id in matchedSyncedIds }
            .map { it.toUiModel() }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        return coreEntries + extras
    }

    private suspend fun launchCoreApp(coreKey: String) {
        val spec = CORE_APPS.firstOrNull { it.key == coreKey }
        if (spec == null) {
            _toastMessage.tryEmit("Ứng dụng cốt lõi không hợp lệ")
            return
        }

        // Dedicated remote keys are often the most reliable across TV brands.
        if (coreKey == "youtube") {
            val launchedByKey = runCatching {
                connectionManager.sendKey(TvKey.YOUTUBE)
                true
            }.getOrDefault(false)
            if (launchedByKey) return
        }
        if (coreKey == "netflix") {
            val launchedByKey = runCatching {
                connectionManager.sendKey(TvKey.NETFLIX)
                true
            }.getOrDefault(false)
            if (launchedByKey) return
        }

        // Then try known package candidates for Android/Google TV style launch paths.
        for (candidate in spec.packageCandidates) {
            val launched = runCatching {
                connectionManager.launchApp(candidate)
                true
            }.getOrDefault(false)
            if (launched) return
        }

        _toastMessage.tryEmit("Không tìm thấy ${spec.displayName} trên TV này")
    }

    private fun observeCastRendererAutoSelection() {
        viewModelScope.launch {
            combine(castRenderers, prefs.lastCastRenderer) { renderers, saved ->
                renderers to saved
            }.collect { (renderers, saved) ->
                if (renderers.isEmpty()) return@collect

                if (!restoredLastCastRenderer && saved != null) {
                    val restored = findMatchingCastRenderer(saved, renderers)
                    if (restored != null) {
                        selectCastRenderer(restored.udn, restored.name, persist = false)
                        restoredLastCastRenderer = true
                        return@collect
                    }
                }

                if (_selectedCastRendererUdn.value == null) {
                    val first = renderers.first()
                    selectCastRenderer(first.udn, first.name, persist = false)
                }
            }
        }
    }

    private fun findMatchingCastRenderer(
        saved: LastCastRenderer,
        renderers: List<DlnaRenderer>
    ): DlnaRenderer? {
        return renderers.firstOrNull { it.udn == saved.udn }
            ?: renderers.firstOrNull { it.name.equals(saved.name, ignoreCase = true) }
    }
}

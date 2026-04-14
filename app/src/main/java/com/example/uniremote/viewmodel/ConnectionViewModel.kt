package com.example.uniremote.viewmodel

import android.app.Application
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.network.DeviceConnectionManager
import com.example.uniremote.network.PairingState
import com.example.uniremote.network.RokuSsdpDiscovery
import com.example.uniremote.network.TvApp
import com.example.uniremote.network.TvAppUiModel
import com.example.uniremote.network.TvKey
import com.example.uniremote.network.toUiModel
import com.example.uniremote.util.WakeOnLanUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

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

internal class ConnectionViewModel(
    private val application: Application,
    private val scope: CoroutineScope,
    private val repo: DeviceRepository,
    private val connectionManager: DeviceConnectionManager,
    private val knownDevices: StateFlow<List<TvDevice>>,
    private val isTextInputActive: () -> Boolean,
    private val emitToast: (String) -> Unit,
    private val reportFailure: (String, Throwable, String) -> Unit
) {
    val connectionStatus: StateFlow<ConnectionStatus> = connectionManager.connectionStatus
    val connectedDevice: StateFlow<TvDevice?> = connectionManager.connectedDevice
    val pairingState: StateFlow<PairingState> = connectionManager.pairingState

    private val _installedApps = MutableStateFlow<List<TvAppUiModel>>(emptyList())
    val installedApps: StateFlow<List<TvAppUiModel>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private var connectJob: Job? = null

    // Drag-to-DPad fallback accumulators for TVs that do not support mouse pointer protocol.
    private var mouseFallbackAccumX = 0f
    private var mouseFallbackAccumY = 0f
    private val mouseFallbackStepPx = 28f

    fun bindTokenPersistence() {
        connectionManager.onTokenReceived = { token ->
            scope.launch {
                val currentDevice = connectedDevice.value
                if (currentDevice != null) {
                    val updated = currentDevice.copy(token = token)
                    repo.saveDevice(updated)
                }
            }
        }
    }

    fun cancelPendingWork() {
        connectJob?.cancel()
        connectJob = null
    }

    fun connectOrPair(device: TvDevice) {
        connectJob?.cancel()
        connectJob = null

        val savedDevice = findPersistedDeviceForMerge(device)
        val effectiveDevice = mergeWithPersistedCredentials(device, savedDevice)
        if (connectionManager.requiresPairing(effectiveDevice) && !effectiveDevice.isPaired) {
            startGoogleTvPairing(effectiveDevice)
        } else {
            connectTo(effectiveDevice)
        }
    }

    fun connectTo(device: TvDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            val savedDevice = findPersistedDeviceForMerge(device)
            val effectiveDevice = mergeWithPersistedCredentials(device, savedDevice)
            val success = connectionManager.connectTo(effectiveDevice)
            if (success) {
                handleConnectSuccess(effectiveDevice)
            } else {
                if (attemptGoogleTvRePairRecovery(effectiveDevice)) {
                    return@launch
                }
                repo.markDeviceOffline(effectiveDevice.id)
                emitToast(connectionErrorMessage(effectiveDevice))
            }
        }
    }

    fun startGoogleTvPairing(device: TvDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            val paired = connectionManager.startGoogleTvPairing(device)
            if (paired) {
                val pairedDevice = device.copy(isPaired = true)
                repo.saveDevice(pairedDevice)
                connectTo(pairedDevice)
            } else {
                val savedDevice = findPersistedDeviceForMerge(device)
                if (savedDevice?.isPaired == true) {
                    connectTo(savedDevice)
                } else {
                    emitToast("Ghep doi bi huy. Vao Settings -> chon TV -> bam Ket noi de thu ghep doi lai.")
                    connectionManager.setDisconnected()
                }
            }
        }
    }

    fun submitPairingPin(pin: String) {
        scope.launch { connectionManager.submitPairingPin(pin) }
    }

    fun cancelPairing() {
        connectionManager.cancelPairing()
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            connectedDevice.value?.id?.let { repo.markDeviceOffline(it) }
            connectionManager.disconnect()
        }
    }

    fun forgetDevice(deviceId: String) {
        scope.launch { repo.forgetDevice(deviceId) }
    }

    fun sendKey(key: TvKey) {
        scope.launch {
            try {
                connectionManager.sendKey(key)
            } catch (e: Exception) {
                reportFailure("sendKey:$key", e, "Tin hieu bi mat, cho tivi phan hoi.")
            }
        }
    }

    fun triggerSearchAction() {
        scope.launch {
            sendKeyWithFallback(
                action = "SEARCH",
                chain = listOf(TvKey.SEARCH, TvKey.MENU, TvKey.SETTINGS),
                unsupportedMessage = "TV hien tai khong ho tro tim kiem."
            )
        }
    }

    fun triggerSourceAction() {
        scope.launch {
            sendKeyWithFallback(
                action = "SOURCE",
                chain = listOf(TvKey.SOURCE, TvKey.SETTINGS, TvKey.MENU),
                unsupportedMessage = "TV hien tai khong ho tro doi nguon vao."
            )
        }
    }

    fun triggerGuideAction() {
        scope.launch {
            sendKeyWithFallback(
                action = "GUIDE",
                chain = listOf(TvKey.GUIDE, TvKey.MENU, TvKey.SETTINGS),
                unsupportedMessage = "TV hien tai khong ho tro guide."
            )
        }
    }

    fun triggerActionMenu() {
        scope.launch {
            sendKeyWithFallback(
                action = "ACTION_MENU",
                chain = listOf(TvKey.MENU, TvKey.SETTINGS),
                unsupportedMessage = "TV hien tai khong ho tro action menu."
            )
        }
    }

    fun triggerInfoAction() {
        scope.launch {
            sendKeyWithFallback(
                action = "INFO",
                chain = listOf(TvKey.INFO, TvKey.MENU, TvKey.SETTINGS),
                unsupportedMessage = "TV hien tai khong ho tro thong tin kenh."
            )
        }
    }

    fun triggerDigitalAnalogAction() {
        scope.launch {
            sendKeyWithFallback(
                action = "DIGITAL_ANALOG",
                chain = listOf(TvKey.CH_UP, TvKey.GUIDE, TvKey.MENU),
                unsupportedMessage = "TV hien tai khong ho tro nut Digital/Analog."
            )
        }
    }

    fun sendText(text: String) {
        scope.launch {
            try {
                connectionManager.sendText(text)
            } catch (e: Exception) {
                reportFailure("sendText", e, "Loi gui phim cung")
            }
        }
    }

    fun sendTextAndEnter(text: String) {
        scope.launch {
            try {
                connectionManager.sendText(text)
                delay(300)
                connectionManager.sendKey(TvKey.OK)
            } catch (e: Exception) {
                reportFailure("sendTextAndEnter", e, "Loi gui doan van ban")
            }
        }
    }

    fun moveMouse(dx: Float, dy: Float) {
        scope.launch {
            try {
                connectionManager.moveMouse(dx, dy)
            } catch (e: UnsupportedOperationException) {
                runCatching { fallbackDirectionalFromDrag(dx, dy) }
                    .onFailure { reportFailure("moveMouseFallback", it, "Khong the dieu huong") }
            } catch (e: Exception) {
                reportFailure("moveMouse", e, "Khong the dieu khien chuot")
            }
        }
    }

    fun tapMouse() {
        scope.launch {
            try {
                connectionManager.tapMouse()
            } catch (e: UnsupportedOperationException) {
                if (isTextInputActive()) {
                    return@launch
                }
                runCatching { connectionManager.sendKey(TvKey.OK) }
                    .onFailure { reportFailure("tapMouseFallback", it, "Khong the chon muc") }
            } catch (e: Exception) {
                reportFailure("tapMouse", e, "Khong the click chuot")
            }
        }
    }

    fun wakeTV() {
        val device = connectedDevice.value ?: return
        val cleanMac = device.mac.replace(":", "").replace("-", "")

        scope.launch(Dispatchers.IO) {
            if (device.brand != TvBrand.ROKU) {
                if (cleanMac.length != 12) {
                    emitToast("Thieu dia chi MAC hop le de bat TV bang WoL")
                    return@launch
                }
                runCatching { WakeOnLanUtil.sendMagicPacket(device.mac) }
                    .onFailure { reportFailure("wakeTV", it, "Khong gui duoc goi WoL") }
                return@launch
            }

            // Roku fallback chain:
            // 1) WoL magic packet (if MAC exists)
            // 2) Retry ECP reachability
            // 3) Send Home key to wake from standby-ready state
            val wolAttempted = cleanMac.length == 12
            if (wolAttempted) {
                runCatching { WakeOnLanUtil.sendMagicPacket(device.mac) }
                    .onFailure { reportFailure("rokuWake:wol", it, "WoL that bai, dang thu ECP") }
                delay(4_000)
            }

            val reachable = runCatching { connectionManager.tryConnectSilently(device) }
                .getOrDefault(false)

            if (!reachable) {
                val connected = runCatching { connectionManager.connectTo(device) }
                    .getOrDefault(false)
                if (!connected) {
                    emitToast("Roku chua online. Kiem tra che do Network Standby tren TV")
                    return@launch
                }
            }

            runCatching { connectionManager.sendKey(TvKey.HOME) }
                .onFailure { reportFailure("rokuWake:home", it, "Khong gui duoc lenh Home toi Roku") }
        }
    }

    fun connectToManualRokuIp(ip: String, name: String = "Roku (Manual IP)") {
        val manual = RokuSsdpDiscovery.buildManualRokuDevice(ip, name)
        if (manual == null) {
            emitToast("IP khong hop le. Chi ho tro dia chi LAN noi bo")
            return
        }
        connectTo(manual)
    }

    fun loadInstalledApps() {
        scope.launch {
            _isLoadingApps.value = true
            try {
                val syncedApps = connectionManager.getInstalledApps()
                _installedApps.value = mergeCoreAppsWithSynced(syncedApps)
            } catch (e: Exception) {
                _installedApps.value = emptyList()
                reportFailure("loadInstalledApps", e, "Khong the tai danh sach ung dung")
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun launchApp(appId: String) {
        scope.launch { launchAppInternal(appId) }
    }

    suspend fun launchAppInternal(appId: String) {
        if (appId.startsWith("core:")) {
            val coreKey = appId.substringAfter("core:")
            launchCoreApp(coreKey)
            return
        }

        try {
            connectionManager.launchApp(appId)
        } catch (e: Exception) {
            reportFailure("launchApp:$appId", e, "Khong the mo ung dung nay tren TV")
        }
    }

    private suspend fun fallbackDirectionalFromDrag(dx: Float, dy: Float) {
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

    private suspend fun sendKeyWithFallback(
        action: String,
        chain: List<TvKey>,
        unsupportedMessage: String
    ) {
        var lastUnsupported: UnsupportedOperationException? = null
        for (key in chain) {
            try {
                connectionManager.sendKey(key)
                return
            } catch (e: UnsupportedOperationException) {
                lastUnsupported = e
            } catch (e: Exception) {
                reportFailure("sendKey:$action", e, "Khong gui duoc lenh $action.")
                return
            }
        }

        if (lastUnsupported != null) {
            reportFailure("sendKey:$action", lastUnsupported, unsupportedMessage)
        } else {
            emitToast(unsupportedMessage)
        }
    }

    private fun findPersistedDeviceForMerge(candidate: TvDevice): TvDevice? {
        val known = knownDevices.value

        known.firstOrNull { it.id == candidate.id }?.let { return it }

        val candidateMac = normalizeMac(candidate.mac)
        if (candidateMac != null) {
            known.firstOrNull { normalizeMac(it.mac) == candidateMac }?.let { return it }
        }

        val nameBrandMatches = known.filter {
            it.brand == candidate.brand && it.name.equals(candidate.name, ignoreCase = true)
        }
        return if (nameBrandMatches.size == 1) nameBrandMatches.first() else null
    }

    private fun mergeWithPersistedCredentials(discovered: TvDevice, saved: TvDevice?): TvDevice {
        if (saved == null) return discovered

        return discovered.copy(
            id = saved.id,
            name = discovered.name.takeIf { it.isNotBlank() } ?: saved.name,
            brand = if (discovered.brand == TvBrand.UNKNOWN) saved.brand else discovered.brand,
            mac = discovered.mac.takeIf { it.isNotBlank() } ?: saved.mac,
            ssid = saved.ssid.takeIf { it.isNotBlank() } ?: discovered.ssid,
            lastConnectedMs = maxOf(saved.lastConnectedMs, discovered.lastConnectedMs),
            token = saved.token ?: discovered.token,
            isPaired = saved.isPaired || discovered.isPaired
        )
    }

    private fun normalizeMac(raw: String): String? {
        val clean = raw.replace(":", "").replace("-", "").trim().uppercase(Locale.US)
        if (clean.isBlank() || clean == "000000000000") return null
        return clean
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
            emitToast("Ung dung cot loi khong hop le")
            return
        }

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

        for (candidate in spec.packageCandidates) {
            val launched = runCatching {
                connectionManager.launchApp(candidate)
                true
            }.getOrDefault(false)
            if (launched) return
        }

        emitToast("Khong tim thay ${spec.displayName} tren TV nay")
    }

    private suspend fun handleConnectSuccess(device: TvDevice) {
        repo.markDeviceOnline(device.id)
        repo.saveDevice(device)
        loadInstalledApps()

        if (connectionManager.isAdbFallbackMode.value) {
            emitToast(
                "Ket noi qua ADB. Neu remote khong phan hoi, hay vao TV -> Developer Options -> bat ADB over Network va chap nhan fingerprint."
            )
        }
    }

    private fun connectionErrorMessage(device: TvDevice): String {
        val statusError = connectionStatus.value as? ConnectionStatus.Error
        return statusError?.message
            ?: "Khong the ket noi voi ${device.name}. Vui long kiem tra lai TV."
    }

    private suspend fun attemptGoogleTvRePairRecovery(device: TvDevice): Boolean {
        val isGoogleTvFamily = device.brand in setOf(
            TvBrand.GOOGLE_TV,
            TvBrand.ANDROID,
            TvBrand.SONY,
            TvBrand.XIAOMI
        )
        if (!isGoogleTvFamily || !device.isPaired) return false

        // If the TV rotated its cert/key, a stale isPaired=true will keep failing.
        // Force one re-pair attempt before surfacing a hard failure.
        val resetPairDevice = device.copy(isPaired = false)
        repo.saveDevice(resetPairDevice)
        emitToast("Ket noi that bai. Dang thu ghep doi lai ${device.name}...")

        val paired = connectionManager.startGoogleTvPairing(resetPairDevice)
        if (!paired) {
            connectionManager.setDisconnected()
            emitToast("Ghep doi lai that bai. Hay thu lai va nhap ma PIN tren TV.")
            return false
        }

        val pairedDevice = resetPairDevice.copy(isPaired = true)
        repo.saveDevice(pairedDevice)

        val retrySuccess = connectionManager.connectTo(pairedDevice)
        if (!retrySuccess) {
            return false
        }

        handleConnectSuccess(pairedDevice)
        emitToast("Da ghep doi lai thanh cong voi ${device.name}.")
        return true
    }
}

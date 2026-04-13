package com.example.uniremote.cast

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.uniremote.network.TransportSecurityPolicy
import com.uniremote.dlna.dlna.DlnaManager
import com.uniremote.dlna.dlna.DlnaRenderer
import com.uniremote.dlna.dlna.DlnaUpnpService
import com.uniremote.dlna.dlna.NanoHttpMediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jupnp.android.AndroidUpnpService
import org.jupnp.support.model.PositionInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

data class CastPlaybackInfo(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int? = null,
    val muted: Boolean? = null
)

/**
 * Manages the full lifecycle of DLNA media casting:
 * - Binds/unbinds [DlnaUpnpService] when the Cast screen is visible.
 * - Runs [NanoHttpMediaServer] (port 8080) to serve local media files to the TV.
 * - Exposes reactive [castState] and [renderers] flows to the ViewModel.
 */
class CastRepository(private val context: Context) {
    companion object {
        private const val TAG = "CastRepository"
        private const val MULTICAST_LOCK_TAG = "UniRemoteDlnaSsdp"
        private const val MAX_CONSECUTIVE_TELEMETRY_FAILURES = 6
    }

    private val appContext = context.applicationContext
    private val ssdpProbe = DlnaSsdpProbe()

    private val _renderers = MutableStateFlow<List<DlnaRenderer>>(emptyList())
    val renderers: StateFlow<List<DlnaRenderer>> = _renderers.asStateFlow()

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()
    private val stateMachine = CastStateMachine(_castState)

    private val _playbackInfo = MutableStateFlow(CastPlaybackInfo())
    val playbackInfo: StateFlow<CastPlaybackInfo> = _playbackInfo.asStateFlow()

    private var mediaServer: NanoHttpMediaServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeRendererUdn: String? = null
    private var discoveryTimeoutJob: Job? = null
    private var discoverySweepJob: Job? = null
    private var playbackPollJob: Job? = null
    private var consecutiveTelemetryFailures: Int = 0

    /** Direct HTTP/SOAP caster — used when jUPnP service is not ready. */
    private val directCaster = DirectDlnaCaster()

    /** Maps renderer UDN → device description URL (from our SSDP probe). */
    private val rendererLocationCache = mutableMapOf<String, String>()

    /** Known device IP from Settings — used for unicast SSDP fallback when multicast fails. */
    @Volatile private var hintDeviceIp: String? = null
    fun setHintDeviceIp(ip: String?) { hintDeviceIp = ip }

    // Scope used for retry logic only — cancelled in release()
    private val repoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val dlnaManager = DlnaManager(
        onDevicesChanged = { devices ->
            _renderers.value = devices
            // End "discovering" once we have at least one renderer.
            if (devices.isNotEmpty() && _castState.value is CastState.Discovering) {
                discoveryTimeoutJob?.cancel()
                discoveryTimeoutJob = null
                stateMachine.onIdle()
            }
        },
        onError = { msg ->
            if (isTelemetryError(msg) && _castState.value is CastState.Casting) {
                consecutiveTelemetryFailures += 1
                if (consecutiveTelemetryFailures < MAX_CONSECUTIVE_TELEMETRY_FAILURES) {
                    return@DlnaManager
                }
            }

            // Only surface errors when the user is actively trying to cast
            if (_castState.value !is CastState.Idle) {
                stateMachine.onError(msg)
                stopPlaybackPolling(resetInfo = false)
            }
        },
        onPosition = { info ->
            consecutiveTelemetryFailures = 0
            val nextPosition = parseUpnpTimeMillis(info.relTime)
            val nextDuration = parseUpnpTimeMillis(info.trackDuration)
            _playbackInfo.value = _playbackInfo.value.copy(
                positionMs = nextPosition,
                durationMs = if (nextDuration > 0L) nextDuration else _playbackInfo.value.durationMs
            )
        },
        onVolume = { level, muted ->
            consecutiveTelemetryFailures = 0
            _playbackInfo.value = _playbackInfo.value.copy(
                volume = level ?: _playbackInfo.value.volume,
                muted = muted ?: _playbackInfo.value.muted
            )
        }
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val upnpService = service as? AndroidUpnpService ?: return
            dlnaManager.bind(upnpService)
            stateMachine.onDiscovering()
            armDiscoveryTimeout()

            // DlnaManager.bind() silently returns early (sets upnpService=null)
            // when AndroidUpnpService.getRegistry() is null — a known jUPnP race
            // condition during startup.  Retry binding after a short delay so the
            // registry has time to initialise.
            bindRetryJob = repoScope.launch {
                var retries = 0
                while (retries < 8) {
                    delay(500L * (retries + 1))   // 500 ms, 1 s, 1.5 s …
                    dlnaManager.bind(upnpService)
                    if (upnpService.registry != null) {
                        startDiscoverySweep()
                        break
                    }
                    retries++
                }

                // Do not surface a blocking error banner here; keep the screen in discovery
                // state and allow manual Scan because jUPnP registry readiness can be delayed.
                if (upnpService.registry == null) {
                    startDiscoverySweep()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dlnaManager.unbind()
        }
    }

    private var isBound = false
    /** Tracks the retry-bind coroutine so it can be cancelled if the user leaves quickly. */
    private var bindRetryJob: kotlinx.coroutines.Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the local HTTP media server and binds the DLNA UPnP service.
     * Call this when the Cast screen becomes visible.
     */
    fun bind() {
        if (isBound) return

        val preflightError = validateDiscoveryPreconditions()
        if (preflightError != null) {
            stateMachine.onError(preflightError)
            return
        }

        if (!TransportSecurityPolicy.allowInsecureDlnaCasting()) {
            stateMachine.onError(
                "DLNA casting is disabled in production mode because it requires plaintext HTTP."
            )
            return
        }

        acquireMulticastLock()

        // Start the local media server (serves files to the TV over HTTP)
        if (mediaServer == null) {
            val server = NanoHttpMediaServer(context, 8080)
            if (runCatching { server.start(5_000, false) }.isSuccess) {
                mediaServer = server
            }
            // If binding port 8080 fails, casting will report an error when attempted.
        }

        val bindSuccess = runCatching {
            appContext.bindService(
                Intent(appContext, DlnaUpnpService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)

        if (!bindSuccess) {
            stateMachine.onError("Could not bind DLNA service. Please retry.")
            isBound = false
            releaseMulticastLock()
            return
        }

        isBound = true
    }

    /**
     * Unbinds the DLNA service and resets discovery state.
     * Call this when the Cast screen is no longer visible.
     */
    fun unbind() {
        if (!isBound) return
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        discoverySweepJob?.cancel()
        discoverySweepJob = null
        // Cancel any in-flight retry coroutine BEFORE calling unbindService.
        // If we unbind while the retry loop is still running, the loop may try
        // to call dlnaManager.bind() on a service that is being destroyed,
        // causing the jUPnP NPE in onDestroy().
        bindRetryJob?.cancel()
        bindRetryJob = null
        runCatching { appContext.unbindService(serviceConnection) }
        dlnaManager.unbind()
        isBound = false
        stopPlaybackPolling(resetInfo = true)
        if (_castState.value !is CastState.Casting) {
            stateMachine.onIdle()
        }
        _renderers.value = emptyList()
        releaseMulticastLock()
    }

    /**
     * Full teardown — stops the media server. Call from [ViewModel.onCleared].
     */
    fun release() {
        // Cancel scope FIRST so unbind() doesn't race with a pending retry coroutine
        repoScope.cancel()
        unbind()
        mediaServer?.stop()
        mediaServer = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return

        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        runCatching {
            lock.acquire()
            multicastLock = lock
        }.onFailure {
            Log.w(TAG, "Failed to acquire multicast lock for DLNA discovery", it)
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        runCatching {
            if (lock.isHeld) {
                lock.release()
            }
        }.onFailure {
            Log.w(TAG, "Failed to release multicast lock", it)
        }
        multicastLock = null
    }

    // ── Operations ────────────────────────────────────────────────────────────

    /** Re-triggers UPnP search for DLNA renderers on the network. */
    fun refresh() {
        stateMachine.onDiscovering()
        armDiscoveryTimeout()
        startDiscoverySweep()
    }

    private fun startDiscoverySweep() {
        discoverySweepJob?.cancel()
        discoverySweepJob = repoScope.launch {
            repeat(3) { index ->
                val attempt = index + 1

                // Also trigger jUPnP search (best-effort; unreliable on many Android devices)
                dlnaManager.refresh()

                // PRIMARY: our own UDP M-SEARCH that actually returns and parses results
                runCatching {
                    val found = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ssdpProbe.discover(timeoutMs = 3000, hintIp = hintDeviceIp)
                    }
                    Log.i(TAG, "SSDP probe attempt=$attempt found=${found.size}: ${found.map { it.name }}")

                    if (found.isNotEmpty()) {
                        val probeRenderers = found.map {
                            com.uniremote.dlna.dlna.DlnaRenderer(udn = it.udn, name = it.name, model = it.model)
                        }
                        // Cache location URLs so DirectDlnaCaster can use them
                        found.forEach { rendererLocationCache[it.udn] = it.location }

                        val merged = (_renderers.value + probeRenderers).distinctBy { it.udn }.sortedBy { it.name }
                        _renderers.value = merged

                        if (_castState.value is CastState.Discovering) {
                            discoveryTimeoutJob?.cancel()
                            discoveryTimeoutJob = null
                            stateMachine.onIdle()
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "SSDP probe attempt=$attempt failed", it)
                }

                if (attempt < 3) delay(1500L)
            }
        }
    }

    private fun validateDiscoveryPreconditions(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "No active network. Connect Wi-Fi to scan DLNA TVs."
        val caps = cm.getNetworkCapabilities(active)
            ?: return "Cannot read active network capabilities for DLNA discovery."

        val hasLanTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!hasLanTransport) {
            return "DLNA discovery requires Wi-Fi/LAN as active network."
        }

        // NOTE: NEARBY_WIFI_DEVICES permission is only required for WifiManager.startScan()
        // and peer-to-peer Wi-Fi APIs. It is NOT required for DLNA SSDP UDP multicast
        // (which uses raw DatagramSocket on port 1900). Removing this gate so jUPnP
        // can always bind and discover renderers on LAN.

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "Disable VPN and retry DLNA scan. VPN can block multicast SSDP."
        }

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(appContext.packageName)) {
            Log.w(TAG, "Battery optimization active; SSDP multicast delivery may be delayed")
        }

        return null
    }

    /** Cast an already-public URL (e.g. mirror stream) to a selected renderer. */
    fun castUrl(
        mediaUrl: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) {
        consecutiveTelemetryFailures = 0
        val routedMediaUrl = adaptLocalUrlForRenderer(mediaUrl, rendererUdn)
        if (!TransportSecurityPolicy.allowCleartextForUrl(routedMediaUrl, "DLNA cast media")) {
            stateMachine.onError("Blocked non-LAN cleartext media URL. Use HTTPS or a local LAN host.")
            return
        }
        activeRendererUdn = rendererUdn
        stateMachine.onSendingUri(title, rendererName)

        val onSuccess:  () -> Unit     = { repoScope.launch { stateMachine.onCasting(title, rendererName) } }
        val onFailure2: (String) -> Unit = { msg -> repoScope.launch {
            stateMachine.onError(msg); stopPlaybackPolling(resetInfo = false)
        }}

        // Try jUPnP first; fall back to DirectDlnaCaster if jUPnP is not ready
        val cachedLocation = rendererLocationCache[rendererUdn]
        dlnaManager.cast(
            rendererUdn = rendererUdn,
            mediaUrl    = routedMediaUrl,
            title       = title,
            onUriAccepted   = { repoScope.launch { stateMachine.onStartingPlayback(title, rendererName) } },
            onPlaybackStarted = { repoScope.launch { stateMachine.onCasting(title, rendererName); startPlaybackPolling(rendererUdn) } },
            onFailure = { jUpnpMsg ->
                repoScope.launch {
                    val location = cachedLocation ?: resolveLocationForFallback(rendererUdn)
                    if (location != null) {
                        Log.w(TAG, "jUPnP cast failed ($jUpnpMsg), falling back to DirectDlnaCaster")
                        directCaster.cast(
                            udn        = rendererUdn,
                            location   = location,
                            mediaUrl   = routedMediaUrl,
                            title      = title,
                            mimeType   = inferMimeTypeFromUrl(routedMediaUrl),
                            onSuccess  = onSuccess,
                            onFailure  = onFailure2
                        )
                    } else {
                        onFailure2(jUpnpMsg)
                    }
                }
            }
        )
    }

    /**
     * Registers [uri] with the local HTTP server and instructs the [rendererUdn]
     * renderer to play it via AVTransport SetAVTransportURI + Play.
     * Falls back to DirectDlnaCaster if jUPnP is not ready.
     */
    fun castMedia(
        uri: Uri,
        mimeType: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) {
        consecutiveTelemetryFailures = 0
        val server = mediaServer ?: run {
            stateMachine.onError("Media server is not available. Please reconnect.")
            return
        }
        val host = getLocalIp() ?: run {
            stateMachine.onError("Cannot resolve local IP. Ensure both devices are on the same Wi-Fi network.")
            return
        }

        val mediaPath = server.setActiveMedia(uri, mimeType, title)
        activeRendererUdn = rendererUdn
        val mediaUrl = "http://$host:8080/$mediaPath"
        stateMachine.onSendingUri(title, rendererName)

        val cachedLocation = rendererLocationCache[rendererUdn]

        val onSuccess:   () -> Unit      = { repoScope.launch { stateMachine.onCasting(title, rendererName); startPlaybackPolling(rendererUdn) } }
        val onFinalFail: (String) -> Unit = { msg -> repoScope.launch { stateMachine.onError(msg); stopPlaybackPolling(resetInfo = false) } }

        dlnaManager.cast(
            rendererUdn = rendererUdn,
            mediaUrl    = mediaUrl,
            title       = title,
            onUriAccepted     = { repoScope.launch { stateMachine.onStartingPlayback(title, rendererName) } },
            onPlaybackStarted = { repoScope.launch { stateMachine.onCasting(title, rendererName); startPlaybackPolling(rendererUdn) } },
            onFailure = { jUpnpMsg ->
                repoScope.launch {
                    val location = cachedLocation ?: resolveLocationForFallback(rendererUdn)
                    if (location != null) {
                        Log.w(TAG, "jUPnP castMedia failed ($jUpnpMsg), trying DirectDlnaCaster")
                        directCaster.cast(
                            udn       = rendererUdn,
                            location  = location,
                            mediaUrl  = mediaUrl,
                            title     = title,
                            mimeType  = mimeType,
                            onSuccess = onSuccess,
                            onFailure = onFinalFail
                        )
                    } else {
                        onFinalFail(jUpnpMsg)
                    }
                }
            }
        )
    }

    /**
     * Registers local media in NanoHTTPD and returns a LAN-reachable HTTP URL.
     * Used by Google Cast flow, which needs an HTTP endpoint instead of file:// URI.
     */
    fun buildLocalMediaUrl(uri: Uri, mimeType: String, title: String): String? {
        val server = ensureMediaServer() ?: return null
        val host = getLocalIp() ?: return null
        val mediaPath = server.setActiveMedia(uri, mimeType, title)
        return "http://$host:8080/$mediaPath"
    }

    /** Sends Stop to the active renderer and clears local state. */
    fun stopCast() {
        val udn = activeRendererUdn
        if (udn != null) {
            val location = rendererLocationCache[udn]
            if (location != null) {
                repoScope.launch { directCaster.stop(udn, location) }
            }
            dlnaManager.stop(udn)  // also try jUPnP (no-op if not ready)
        }
        mediaServer?.clearActiveMedia()
        activeRendererUdn = null
        consecutiveTelemetryFailures = 0
        stopPlaybackPolling(resetInfo = true)
        stateMachine.onIdle()
    }

    fun playCast() {
        val udn = activeRendererUdn ?: return
        val location = rendererLocationCache[udn]
        if (location != null) {
            repoScope.launch { directCaster.play(udn, location) }
        }
        dlnaManager.play(udn)  // also try jUPnP
    }

    fun pauseCast() {
        val udn = activeRendererUdn ?: return
        val location = rendererLocationCache[udn]
        if (location != null) {
            repoScope.launch { directCaster.pause(udn, location) }
        }
        dlnaManager.pause(udn)  // also try jUPnP
    }

    fun seekBy(deltaMs: Long) {
        val rendererUdn = activeRendererUdn ?: return
        val current = _playbackInfo.value
        val upper = if (current.durationMs > 0L) current.durationMs else Long.MAX_VALUE
        val next = (current.positionMs + deltaMs).coerceIn(0L, upper)
        dlnaManager.seekTo(rendererUdn, next)
        _playbackInfo.value = current.copy(positionMs = next)
    }

    fun seekTo(positionMs: Long) {
        val rendererUdn = activeRendererUdn ?: return
        val current = _playbackInfo.value
        val upper = if (current.durationMs > 0L) current.durationMs else Long.MAX_VALUE
        val next = positionMs.coerceIn(0L, upper)
        dlnaManager.seekTo(rendererUdn, next)
        _playbackInfo.value = current.copy(positionMs = next)
    }

    fun changeVolumeBy(step: Int) {
        val rendererUdn = activeRendererUdn ?: return
        val current = _playbackInfo.value.volume ?: 20
        val next = (current + step).coerceIn(0, 100)
        dlnaManager.setVolume(rendererUdn, next)
    }

    fun setVolume(level: Int) {
        val rendererUdn = activeRendererUdn ?: return
        val safeLevel = level.coerceIn(0, 100)
        dlnaManager.setVolume(rendererUdn, safeLevel)
        _playbackInfo.value = _playbackInfo.value.copy(volume = safeLevel)
    }

    fun toggleMute() {
        val rendererUdn = activeRendererUdn ?: return
        val muted = _playbackInfo.value.muted ?: false
        dlnaManager.setMute(rendererUdn, !muted)
    }

    private fun armDiscoveryTimeout(timeoutMs: Long = 30_000L) {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = repoScope.launch {
            delay(timeoutMs)
            if (_castState.value is CastState.Discovering) {
                stateMachine.onIdle()
            }
        }
    }

    private fun startPlaybackPolling(rendererUdn: String) {
        stopPlaybackPolling(resetInfo = true)
        playbackPollJob = repoScope.launch {
            while (true) {
                dlnaManager.fetchPosition(rendererUdn)
                dlnaManager.fetchVolume(rendererUdn)
                delay(1_000L)
            }
        }
    }

    private fun stopPlaybackPolling(resetInfo: Boolean) {
        playbackPollJob?.cancel()
        playbackPollJob = null
        if (resetInfo) {
            _playbackInfo.value = CastPlaybackInfo()
        }
    }

    private fun parseUpnpTimeMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.substringBefore('.')
        val parts = clean.split(':')
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val s = parts[2].toLongOrNull() ?: return 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L
    }

    private fun isTelemetryError(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.startsWith("get position failed") ||
            normalized.startsWith("get volume failed") ||
            normalized.startsWith("get mute failed")
    }

    /**
     * If [url] points back to this phone (localhost/device IP), rewrite its host to the
     * interface address that routes to [rendererUdn]. This avoids multi-NIC/subnet cast failures.
     */
    private fun adaptLocalUrlForRenderer(url: String, rendererUdn: String): String {
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return url
        if (scheme != "http" && scheme != "https") return url

        val host = parsed.host?.lowercase(Locale.US) ?: return url
        if (!isLocalHost(host)) return url

        val routedIp = dlnaManager.resolveLocalIpForRenderer(rendererUdn) ?: return url
        if (host == routedIp) return url

        val authority = if (parsed.port != -1) "$routedIp:${parsed.port}" else routedIp
        return parsed.buildUpon()
            .encodedAuthority(authority)
            .build()
            .toString()
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
            return true
        }

        val knownHosts = mutableSetOf<String>()
        getLocalIp()?.let { knownHosts.add(it.lowercase(Locale.US)) }

        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return host in knownHosts
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.lowercase(Locale.US)?.let { knownHosts.add(it) }
                }
            }
        }

        return host in knownHosts
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Returns the device's local IPv4 address (excluding loopback), or null. */
    fun getLocalIp(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val linkProps = cm.getLinkProperties(activeNetwork)
            val fromActiveNetwork = linkProps
                ?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?.lowercase(Locale.US)
            if (!fromActiveNetwork.isNullOrBlank()) {
                return fromActiveNetwork
            }
        }

        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress?.lowercase(Locale.US)
                }
            }
        }
        return null
    }

    private fun ensureMediaServer(): NanoHttpMediaServer? {
        mediaServer?.let { return it }

        val server = NanoHttpMediaServer(context, 8080)
        return if (runCatching { server.start(5_000, false) }.isSuccess) {
            mediaServer = server
            server
        } else {
            null
        }
    }

    private suspend fun resolveLocationForFallback(rendererUdn: String): String? {
        rendererLocationCache[rendererUdn]?.let { return it }

        val discovered = kotlinx.coroutines.withContext(Dispatchers.IO) {
            ssdpProbe.discover(timeoutMs = 2_000, hintIp = hintDeviceIp)
        }

        val match = discovered.firstOrNull {
            sameRendererUdn(it.udn, rendererUdn)
        } ?: return null

        rendererLocationCache[rendererUdn] = match.location
        return match.location
    }

    private fun sameRendererUdn(a: String, b: String): Boolean {
        return canonicalUdn(a) == canonicalUdn(b)
    }

    private fun canonicalUdn(udn: String): String {
        return udn.trim().removePrefix("uuid:").lowercase(Locale.US)
    }

    private fun inferMimeTypeFromUrl(url: String): String {
        val lower = url.substringBefore('?').lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.endsWith(".h264") || lower.endsWith(".avc") -> "video/avc"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            else -> "video/mp4"
        }
    }
}

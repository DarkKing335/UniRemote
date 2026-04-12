package com.example.uniremote.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Uri
import android.os.IBinder
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
    private val appContext = context.applicationContext

    private val _renderers = MutableStateFlow<List<DlnaRenderer>>(emptyList())
    val renderers: StateFlow<List<DlnaRenderer>> = _renderers.asStateFlow()

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()
    private val stateMachine = CastStateMachine(_castState)

    private val _playbackInfo = MutableStateFlow(CastPlaybackInfo())
    val playbackInfo: StateFlow<CastPlaybackInfo> = _playbackInfo.asStateFlow()

    private var mediaServer: NanoHttpMediaServer? = null
    private var activeRendererUdn: String? = null
    private var discoveryTimeoutJob: Job? = null
    private var playbackPollJob: Job? = null

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
            // Only surface errors when the user is actively trying to cast
            if (_castState.value !is CastState.Idle) {
                stateMachine.onError(msg)
                stopPlaybackPolling(resetInfo = false)
            }
        },
        onPosition = { info ->
            val nextPosition = parseUpnpTimeMillis(info.relTime)
            val nextDuration = parseUpnpTimeMillis(info.trackDuration)
            _playbackInfo.value = _playbackInfo.value.copy(
                positionMs = nextPosition,
                durationMs = if (nextDuration > 0L) nextDuration else _playbackInfo.value.durationMs
            )
        },
        onVolume = { level, muted ->
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
                        dlnaManager.refresh()
                        break
                    }
                    retries++
                }

                // Do not surface a blocking error banner here; keep the screen in discovery
                // state and allow manual Scan because jUPnP registry readiness can be delayed.
                if (upnpService.registry == null) {
                    dlnaManager.refresh()
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

        if (!TransportSecurityPolicy.allowInsecureDlnaCasting()) {
            stateMachine.onError(
                "DLNA casting is disabled in production mode because it requires plaintext HTTP."
            )
            return
        }

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
    }

    // ── Operations ────────────────────────────────────────────────────────────

    /** Re-triggers UPnP search for DLNA renderers on the network. */
    fun refresh() {
        stateMachine.onDiscovering()
        armDiscoveryTimeout()
        dlnaManager.refresh()
    }

    /** Cast an already-public URL (e.g. mirror stream) to a selected renderer. */
    fun castUrl(
        mediaUrl: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) {
        activeRendererUdn = rendererUdn
        stateMachine.onSendingUri(title, rendererName)

        dlnaManager.cast(
            rendererUdn = rendererUdn,
            mediaUrl = mediaUrl,
            title = title,
            onUriAccepted = {
                repoScope.launch {
                    stateMachine.onStartingPlayback(title, rendererName)
                }
            },
            onPlaybackStarted = {
                repoScope.launch {
                    stateMachine.onCasting(title, rendererName)
                    startPlaybackPolling(rendererUdn)
                }
            },
            onFailure = { message ->
                repoScope.launch {
                    stateMachine.onError(message)
                    stopPlaybackPolling(resetInfo = false)
                }
            }
        )
    }

    /**
     * Registers [uri] with the local HTTP server and instructs the [rendererUdn]
     * renderer to play it via AVTransport SetAVTransportURI + Play.
     */
    fun castMedia(
        uri: Uri,
        mimeType: String,
        title: String,
        rendererUdn: String,
        rendererName: String
    ) {
        val server = mediaServer ?: run {
            stateMachine.onError("Media server is not available. Please reconnect.")
            return
        }
        val host = getLocalIp() ?: run {
            stateMachine.onError(
                "Cannot resolve local IP. Ensure both devices are on the same Wi-Fi network."
            )
            return
        }

        val mediaPath = server.setActiveMedia(uri, mimeType, title)
        activeRendererUdn = rendererUdn

        val mediaUrl = "http://$host:8080/$mediaPath"
        stateMachine.onSendingUri(title, rendererName)

        dlnaManager.cast(
            rendererUdn = rendererUdn,
            mediaUrl = mediaUrl,
            title = title,
            onUriAccepted = {
                repoScope.launch {
                    stateMachine.onStartingPlayback(title, rendererName)
                }
            },
            onPlaybackStarted = {
                repoScope.launch {
                    stateMachine.onCasting(title, rendererName)
                    startPlaybackPolling(rendererUdn)
                }
            },
            onFailure = { message ->
                repoScope.launch {
                    stateMachine.onError(message)
                    stopPlaybackPolling(resetInfo = false)
                }
            }
        )
    }

    /** Sends Stop to the active renderer and clears local state. */
    fun stopCast() {
        activeRendererUdn?.let { dlnaManager.stop(it) }
        mediaServer?.clearActiveMedia()
        activeRendererUdn = null
        stopPlaybackPolling(resetInfo = true)
        stateMachine.onIdle()
    }

    fun playCast() {
        val rendererUdn = activeRendererUdn ?: return
        dlnaManager.play(rendererUdn)
    }

    fun pauseCast() {
        val rendererUdn = activeRendererUdn ?: return
        dlnaManager.pause(rendererUdn)
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

    private fun armDiscoveryTimeout(timeoutMs: Long = 10_000L) {
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
}

package com.example.uniremote.cast

import android.app.Application
import android.content.Intent
import com.example.uniremote.casting.dlna.DlnaCastEngine
import com.example.uniremote.mirroring.stream.MirroringStreamCoordinator
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

private enum class CastProtocol {
    DLNA,
    GOOGLE_CAST
}

/**
 * Unified production cast manager that keeps DLNA casting and screen mirroring separated.
 */
class DefaultCastManager(
    application: Application,
    castRepository: CastRepository = CastRepository(application)
) : CastManager {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val dlnaEngine = DlnaCastEngine(castRepository)
    private val googleCastManager: GoogleCastManager? = runCatching {
        GoogleCastManager(application)
    }.getOrNull()
    private val mirroringCoordinator = MirroringStreamCoordinator(application)
    private var selectedProtocol: CastProtocol = CastProtocol.DLNA
    private val selectedProtocolFlow = MutableStateFlow(CastProtocol.DLNA)
    private var selectedRendererName: String? = null

    private val emptyRendererFlow = MutableStateFlow<List<DlnaRenderer>>(emptyList())
    private val emptyStateFlow = MutableStateFlow<CastState>(CastState.Idle)
    private val emptyPlaybackFlow = MutableStateFlow(CastPlaybackInfo())

    private val googleRenderers = googleCastManager?.renderers ?: emptyRendererFlow
    private val googleState = googleCastManager?.castState ?: emptyStateFlow
    private val googlePlaybackInfo = googleCastManager?.playbackInfo ?: emptyPlaybackFlow

    val castRenderers: StateFlow<List<DlnaRenderer>> = combine(
        dlnaEngine.rawRenderers,
        googleRenderers
    ) { dlna, google ->
        (google + dlna).distinctBy { it.udn }.sortedBy { it.name }
    }.stateIn(managerScope, SharingStarted.Eagerly, emptyList())

    val castState: StateFlow<CastState> = combine(
        dlnaEngine.castState,
        googleState,
        selectedProtocolFlow
    ) { dlna, google, selected ->
        if (selected == CastProtocol.GOOGLE_CAST) {
            return@combine when {
                google !is CastState.Idle && google !is CastState.Discovering -> google
                google is CastState.Discovering -> google
                else -> CastState.Idle
            }
        }

        when {
            google !is CastState.Idle && google !is CastState.Discovering -> google
            dlna !is CastState.Idle && dlna !is CastState.Discovering -> dlna
            google is CastState.Discovering || dlna is CastState.Discovering -> CastState.Discovering
            else -> CastState.Idle
        }
    }.stateIn(managerScope, SharingStarted.Eagerly, CastState.Idle)

    val castPlaybackInfo: StateFlow<CastPlaybackInfo> = combine(
        dlnaEngine.playbackInfo,
        googlePlaybackInfo,
        googleState
    ) { dlnaInfo, googleInfo, google ->
        if (google !is CastState.Idle && google !is CastState.Discovering) googleInfo else dlnaInfo
    }.stateIn(managerScope, SharingStarted.Eagerly, CastPlaybackInfo())

    val isMirroring = mirroringCoordinator.isMirroring
    val mirrorStreamUrl = mirroringCoordinator.streamUrl
    val mirrorAuthHint = mirroringCoordinator.authHint
    val mirrorTlsFingerprint = mirroringCoordinator.tlsFingerprint

    val isCastingActive: StateFlow<Boolean> = combine(castState, isMirroring) { state, mirroring ->
        state is CastState.Casting || mirroring
    }.stateIn(managerScope, SharingStarted.Eagerly, false)

    fun bind() {
        dlnaEngine.startDiscovery()
        googleCastManager?.startDiscovery()
    }

    fun unbind() {
        dlnaEngine.stopDiscovery()
        googleCastManager?.stopDiscovery()
    }

    fun selectRenderer(rendererUdn: String, rendererName: String) {
        selectedRendererName = rendererName
        if (googleCastManager != null && googleCastManager.isGoogleCastRenderer(rendererUdn)) {
            selectedProtocol = CastProtocol.GOOGLE_CAST
            selectedProtocolFlow.value = CastProtocol.GOOGLE_CAST
            googleCastManager.selectRenderer(rendererUdn, rendererName)
        } else {
            selectedProtocol = CastProtocol.DLNA
            selectedProtocolFlow.value = CastProtocol.DLNA
            dlnaEngine.selectRenderer(rendererUdn, rendererName)
        }
    }

    fun configureMirroringProjection(resultCode: Int, data: Intent) {
        mirroringCoordinator.setProjectionGrant(resultCode, data)
    }

    fun stopCast() {
        googleCastManager?.stop()
        dlnaEngine.stopCast()
    }

    fun changeVolumeBy(step: Int) {
        val current = castPlaybackInfo.value.volume ?: 20
        setVolume((current + step).coerceIn(0, 100))
    }

    fun toggleMute() {
        if (shouldUseGoogleCast()) {
            googleCastManager?.toggleMute()
        } else {
            dlnaEngine.toggleMute()
        }
    }

    fun getMirrorAuthorizationHeaderForManualShare(): String? {
        return mirroringCoordinator.getAuthorizationHeaderForManualShare()
    }

    fun setHintDeviceIp(ip: String?) {
        dlnaEngine.setHintIp(ip)
    }

    override fun discoverDevices() {
        dlnaEngine.startDiscovery()  // bind + single refresh (no double-cancel)
        googleCastManager?.startDiscovery()
    }

    override fun castMedia(url: String) {
        val mediaTitle = url.substringAfterLast('/').ifBlank { "Media" }
        castMedia(url = url, title = mediaTitle, mimeType = "video/mp4")
    }

    fun castMedia(url: String, title: String, mimeType: String) {
        if (shouldUseGoogleCast()) {
            googleCastManager?.castMedia(url = url, title = title, mimeType = mimeType)
        } else {
            dlnaEngine.sendMediaUrl(url = url, title = title)
        }
    }

    override fun play() {
        if (shouldUseGoogleCast()) {
            googleCastManager?.play()
        } else {
            dlnaEngine.play()
        }
    }

    override fun pause() {
        if (shouldUseGoogleCast()) {
            googleCastManager?.pause()
        } else {
            dlnaEngine.pause()
        }
    }

    override fun seek(position: Long) {
        if (shouldUseGoogleCast()) {
            googleCastManager?.seek(position)
        } else {
            dlnaEngine.seek(position)
        }
    }

    override fun setVolume(value: Int) {
        val safeValue = value.coerceIn(0, 100)
        if (shouldUseGoogleCast()) {
            googleCastManager?.setVolume(safeValue)
        } else {
            dlnaEngine.setVolume(safeValue)
        }
    }

    override fun startMirroring() {
        mirroringCoordinator.start { publicEndpoint ->
            // Chromecast default receiver does not support raw Annex-B H264 streams
            // (e.g. /screen.h264). Keep the local mirror endpoint available for manual
            // playback, and only auto-push mirror URL to DLNA renderers.
            if (shouldUseGoogleCast()) {
                googleCastManager?.clearErrorState()
                return@start
            }

            // Sony Bravia/KDL DLNA renderers commonly reject live Annex-B H264 endpoints
            // with UPnP 501 on SetAVTransportURI, while file/image casting still works.
            // Skip auto-push to prevent repeated error state spam.
            if (isLikelySonyDlnaRenderer()) {
                return@start
            }

            runCatching {
                castMedia(
                    url = publicEndpoint,
                    title = "Screen Mirror",
                    mimeType = "video/avc"
                )
            }
        }
    }

    override fun stopMirroring() {
        mirroringCoordinator.stop()
    }

    fun release() {
        mirroringCoordinator.release()
        googleCastManager?.release()
        dlnaEngine.release()
        managerScope.cancel()
    }

    private fun shouldUseGoogleCast(): Boolean {
        val activeGoogleState = googleState.value
        return googleCastManager != null && (
            selectedProtocol == CastProtocol.GOOGLE_CAST ||
                (activeGoogleState !is CastState.Idle && activeGoogleState !is CastState.Discovering)
            )
    }

    private fun isLikelySonyDlnaRenderer(): Boolean {
        if (selectedProtocol != CastProtocol.DLNA) return false
        val name = selectedRendererName?.lowercase(Locale.US) ?: return false
        return name.contains("sony") || name.contains("bravia") || name.contains("kdl")
    }
}

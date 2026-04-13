package com.example.uniremote.cast

import android.app.Application
import android.content.Intent
import com.example.uniremote.casting.dlna.DlnaCastEngine
import com.example.uniremote.mirroring.stream.MirroringStreamCoordinator
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Unified production cast manager that keeps DLNA casting and screen mirroring separated.
 */
class DefaultCastManager(
    application: Application,
    castRepository: CastRepository = CastRepository(application)
) : CastManager {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val dlnaEngine = DlnaCastEngine(castRepository)
    private val mirroringCoordinator = MirroringStreamCoordinator(application)

    val castRenderers: StateFlow<List<DlnaRenderer>> = dlnaEngine.rawRenderers
    val castState: StateFlow<CastState> = dlnaEngine.castState
    val castPlaybackInfo: StateFlow<CastPlaybackInfo> = dlnaEngine.playbackInfo

    val isMirroring = mirroringCoordinator.isMirroring
    val mirrorStreamUrl = mirroringCoordinator.streamUrl
    val mirrorAuthHint = mirroringCoordinator.authHint
    val mirrorTlsFingerprint = mirroringCoordinator.tlsFingerprint

    val isCastingActive: StateFlow<Boolean> = combine(castState, isMirroring) { state, mirroring ->
        state is CastState.Casting || mirroring
    }.stateIn(managerScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun bind() {
        dlnaEngine.startDiscovery()
    }

    fun unbind() {
        dlnaEngine.stopDiscovery()
    }

    fun selectRenderer(rendererUdn: String, rendererName: String) {
        dlnaEngine.selectRenderer(rendererUdn, rendererName)
    }

    fun configureMirroringProjection(resultCode: Int, data: Intent) {
        mirroringCoordinator.setProjectionGrant(resultCode, data)
    }

    fun stopCast() {
        dlnaEngine.stopCast()
    }

    fun changeVolumeBy(step: Int) {
        val current = castPlaybackInfo.value.volume ?: 20
        setVolume((current + step).coerceIn(0, 100))
    }

    fun toggleMute() {
        dlnaEngine.toggleMute()
    }

    fun getMirrorAuthorizationHeaderForManualShare(): String? {
        return mirroringCoordinator.getAuthorizationHeaderForManualShare()
    }

    override fun discoverDevices() {
        dlnaEngine.startDiscovery()
        dlnaEngine.refreshDiscovery()
    }

    override fun castMedia(url: String) {
        val mediaTitle = url.substringAfterLast('/').ifBlank { "Media" }
        dlnaEngine.sendMediaUrl(url = url, title = mediaTitle)
    }

    override fun play() {
        dlnaEngine.play()
    }

    override fun pause() {
        dlnaEngine.pause()
    }

    override fun seek(position: Long) {
        dlnaEngine.seek(position)
    }

    override fun setVolume(value: Int) {
        dlnaEngine.setVolume(value.coerceIn(0, 100))
    }

    override fun startMirroring() {
        mirroringCoordinator.start { publicEndpoint ->
            // Mirroring stream publishing is still URL-based DLNA casting.
            runCatching { castMedia(publicEndpoint) }
        }
    }

    override fun stopMirroring() {
        mirroringCoordinator.stop()
    }

    fun release() {
        mirroringCoordinator.release()
        dlnaEngine.release()
        managerScope.cancel()
    }
}

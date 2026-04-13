package com.example.uniremote.casting.dlna

import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastState
import com.example.uniremote.casting.sender.MediaUrlSender
import com.example.uniremote.core.control.PlaybackControl
import com.example.uniremote.core.device.CastDevice
import com.example.uniremote.core.discovery.SsdpUpnpDiscovery
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * DLNA engine backed by jUPnP discovery/control.
 * Discovery uses SSDP through jUPnP ControlPoint search.
 */
class DlnaCastEngine(
    private val castRepository: CastRepository
) : SsdpUpnpDiscovery, MediaUrlSender, PlaybackControl {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var selectedRendererUdn: String? = null
    private var selectedRendererName: String? = null

    val rawRenderers: StateFlow<List<DlnaRenderer>> = castRepository.renderers
    val castState: StateFlow<CastState> = castRepository.castState
    val playbackInfo: StateFlow<CastPlaybackInfo> = castRepository.playbackInfo

    override val devices: StateFlow<List<CastDevice>> = castRepository.renderers
        .map { renderers ->
            renderers.map {
                CastDevice(
                    id = it.udn,
                    name = it.name,
                    model = it.model,
                    protocol = "DLNA"
                )
            }
        }
        .stateIn(engineScope, SharingStarted.Eagerly, emptyList())

    override fun startDiscovery() {
        castRepository.bind()
        castRepository.refresh()
    }

    override fun refreshDiscovery() {
        castRepository.refresh()
    }

    override fun stopDiscovery() {
        castRepository.unbind()
    }

    fun selectRenderer(udn: String, name: String) {
        selectedRendererUdn = udn
        selectedRendererName = name
    }

    override fun sendMediaUrl(url: String, title: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "DLNA media casting only accepts HTTP(S) URLs"
        }

        val rendererUdn = selectedRendererUdn
            ?: throw IllegalStateException("No DLNA renderer selected")
        val rendererName = selectedRendererName ?: "DLNA Renderer"

        castRepository.castUrl(
            mediaUrl = url,
            title = title,
            rendererUdn = rendererUdn,
            rendererName = rendererName
        )
    }

    fun stopCast() {
        castRepository.stopCast()
    }

    override fun play() {
        castRepository.playCast()
    }

    override fun pause() {
        castRepository.pauseCast()
    }

    override fun seek(positionMs: Long) {
        castRepository.seekTo(positionMs)
    }

    override fun setVolume(value: Int) {
        castRepository.setVolume(value)
    }

    fun toggleMute() {
        castRepository.toggleMute()
    }

    fun release() {
        castRepository.release()
        engineScope.cancel()
    }
}

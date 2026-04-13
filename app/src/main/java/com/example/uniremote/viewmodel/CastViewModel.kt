package com.example.uniremote.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastState
import com.example.uniremote.cast.DefaultCastManager
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.LastCastRenderer
import com.example.uniremote.data.TvDevice
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

private const val CAST_REFRESH_INTERVAL_MS = 20_000L

internal class CastViewModel(
    private val application: Application,
    private val scope: CoroutineScope,
    private val prefs: AppPreferences,
    private val castRepository: CastRepository,
    private val castManager: DefaultCastManager,
    private val connectedDevice: StateFlow<TvDevice?>,
    private val emitToast: (String) -> Unit,
    private val reportFailure: (String, Throwable, String) -> Unit
) {
    private val _isCastFeatureAvailable = MutableStateFlow(true)
    val isCastFeatureAvailable: StateFlow<Boolean> = _isCastFeatureAvailable.asStateFlow()

    val castRenderers = castManager.castRenderers
    val castState: StateFlow<CastState> = castManager.castState
    val castPlaybackInfo: StateFlow<CastPlaybackInfo> = castManager.castPlaybackInfo
    val mirrorStreamUrl: StateFlow<String?> = castManager.mirrorStreamUrl
    val mirrorAuthHint: StateFlow<String?> = castManager.mirrorAuthHint
    val mirrorTlsFingerprint: StateFlow<String?> = castManager.mirrorTlsFingerprint
    val isMirroring: StateFlow<Boolean> = castManager.isMirroring

    private val _selectedCastRendererUdn = MutableStateFlow<String?>(null)
    val selectedCastRendererUdn: StateFlow<String?> = _selectedCastRendererUdn.asStateFlow()

    private val _selectedCastRendererName = MutableStateFlow<String?>(null)
    val selectedCastRendererName: StateFlow<String?> = _selectedCastRendererName.asStateFlow()

    private var castRefreshJob: Job? = null
    private var castDiscoveryStarted = false
    private var restoredLastCastRenderer = false

    fun requestCastFeatureInfo() {
        // Feature is now live — this is a no-op kept for backward compat.
    }

    fun ensureCastDiscoveryStarted() {
        if (castDiscoveryStarted) return
        castDiscoveryStarted = true
        castManager.bind()
        observeCastRendererAutoSelection()

        refreshCastDevicesInternal()
        castRefreshJob = scope.launch {
            while (true) {
                delay(CAST_REFRESH_INTERVAL_MS)
                refreshCastDevicesInternal()
            }
        }
    }

    fun bindCastService() = ensureCastDiscoveryStarted()

    fun unbindCastService() = Unit

    fun refreshCastDevices() {
        ensureCastDiscoveryStarted()
        refreshCastDevicesInternal()
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
            scope.launch { prefs.saveLastCastRenderer(rendererUdn, rendererName) }
        }
    }

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
                reportFailure("castMediaUrl", it, "Khong the cast URL media toi TV")
            }
            return
        }

        val resolvedMimeType = mimeType.ifBlank {
            application.contentResolver.getType(uri) ?: "video/*"
        }

        if (rendererUdn.startsWith("gcast:")) {
            runCatching {
                val localMediaUrl = castRepository.buildLocalMediaUrl(
                    uri = uri,
                    mimeType = resolvedMimeType,
                    title = title
                ) ?: error("Cannot prepare local media URL for Chromecast")

                castManager.castMedia(
                    url = localMediaUrl,
                    title = title,
                    mimeType = resolvedMimeType
                )
            }.onFailure {
                reportFailure("castLocalMediaGoogleCast", it, "Khong the cast file local len Chromecast")
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
            reportFailure("castLocalMedia", it, "Khong the cast file media tu dien thoai")
        }
    }

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
                    emitToast(
                        "Chromecast chua tu phat duoc luong Screen Mirroring (.h264). Dung Cast Video/Image hoac tinh nang Cast man hinh he thong."
                    )
                } else if (isLikelySonyRenderer(rendererName)) {
                    emitToast(
                        "Sony MediaRenderer thuong khong ho tro stream mirroring .h264 truc tiep. Dung Cast Video/Image hoac Cast man hinh he thong."
                    )
                }
            }
            castManager.configureMirroringProjection(resultCode, data)
            castManager.startMirroring()
        }.onFailure {
            reportFailure("startMirroring", it, "Khong the bat dau phan chieu man hinh")
        }
    }

    fun getMirrorAuthorizationHeaderForManualShare(): String? =
        castManager.getMirrorAuthorizationHeaderForManualShare()

    fun onMirroringPermissionDenied() {
        castManager.stopMirroring()
        emitToast("Ban can cap quyen thong bao de bat dau phan chieu man hinh")
    }

    fun stopMirroring() = castManager.stopMirroring()

    fun toggleMirroring() {
        if (isMirroring.value) stopMirroring()
    }

    fun onCleared() {
        castRefreshJob?.cancel()
        castRefreshJob = null
        castManager.release()
    }

    private fun refreshCastDevicesInternal() {
        castManager.setHintDeviceIp(connectedDevice.value?.ip)
        castManager.discoverDevices()
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

    private fun observeCastRendererAutoSelection() {
        scope.launch {
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

    private fun isLikelySonyRenderer(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.US)
        return normalized.contains("sony") || normalized.contains("bravia") || normalized.contains("kdl")
    }
}

package com.example.uniremote.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoogleCastManager(context: Context) {

    companion object {
        private const val TAG = "GoogleCastManager"
        private const val GOOGLE_CAST_PREFIX = "gcast:"
    }

    private data class PendingLoad(
        val url: String,
        val title: String,
        val rendererName: String,
        val mimeType: String
    )

    private val appContext = context.applicationContext

    private val _renderers = MutableStateFlow<List<DlnaRenderer>>(emptyList())
    val renderers: StateFlow<List<DlnaRenderer>> = _renderers.asStateFlow()

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _playbackInfo = MutableStateFlow(CastPlaybackInfo())
    val playbackInfo: StateFlow<CastPlaybackInfo> = _playbackInfo.asStateFlow()

    private val routeSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
        )
        .build()

    private val mediaRouter: MediaRouter by lazy { MediaRouter.getInstance(appContext) }
    private val routes = linkedMapOf<String, MediaRouter.RouteInfo>()

    private var selectedRouteId: String? = null
    private var pendingLoad: PendingLoad? = null

    private var isRouteCallbackRegistered = false
    private var isSessionListenerRegistered = false

    private var remoteMediaClient: RemoteMediaClient? = null

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshRoutes()
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshRoutes()
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshRoutes()
        }

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            selectedRouteId = route.id
        }
    }

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            updatePlaybackInfoFromClient()
        }

        override fun onMetadataUpdated() {
            updatePlaybackInfoFromClient()
        }

        override fun onQueueStatusUpdated() {
            updatePlaybackInfoFromClient()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachSession(session)
            loadPendingIfAny(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _castState.value = CastState.Error("Chromecast session failed to start ($error)")
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachRemoteClient()
            if (_castState.value !is CastState.Discovering) {
                _castState.value = CastState.Idle
            }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachSession(session)
            loadPendingIfAny(session)
            updatePlaybackInfoFromClient()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _castState.value = CastState.Error("Chromecast resume failed ($error)")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            updatePlaybackInfoFromClient()
        }
    }

    fun startDiscovery() {
        if (!isSessionListenerRegistered) {
            currentCastContext()?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            isSessionListenerRegistered = true
        }

        if (!isRouteCallbackRegistered) {
            mediaRouter.addCallback(
                routeSelector,
                routeCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
            )
            isRouteCallbackRegistered = true
        }

        refreshRoutes()
        attachSession(currentCastSession())

        if (_renderers.value.isEmpty() && _castState.value is CastState.Idle) {
            _castState.value = CastState.Discovering
        }
    }

    fun stopDiscovery() {
        if (isRouteCallbackRegistered) {
            runCatching { mediaRouter.removeCallback(routeCallback) }
            isRouteCallbackRegistered = false
        }

        if (isSessionListenerRegistered) {
            runCatching {
                currentCastContext()?.sessionManager
                    ?.removeSessionManagerListener(sessionListener, CastSession::class.java)
            }
            isSessionListenerRegistered = false
        }

        if (_castState.value is CastState.Discovering) {
            _castState.value = CastState.Idle
        }
    }

    fun selectRenderer(rendererUdn: String, rendererName: String) {
        if (!isGoogleCastRenderer(rendererUdn)) return
        val routeId = rendererUdn.removePrefix(GOOGLE_CAST_PREFIX)
        if (routeId.isNotBlank()) {
            selectedRouteId = routeId
        }
    }

    fun castMedia(url: String, title: String, mimeType: String = inferMimeType(url)) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _castState.value = CastState.Error("Chromecast supports HTTP(S) URLs only")
            return
        }

        val route = resolveSelectedRoute()
        if (route == null) {
            _castState.value = CastState.Error("No Chromecast device found")
            return
        }

        val rendererName = route.name?.toString()?.ifBlank { "Chromecast" } ?: "Chromecast"
        pendingLoad = PendingLoad(url = url, title = title, rendererName = rendererName, mimeType = mimeType)
        _castState.value = CastState.SendingUri(title, rendererName)

        val activeSession = currentCastSession()
        if (activeSession?.isConnected == true) {
            loadPendingIfAny(activeSession)
            return
        }

        runCatching {
            mediaRouter.selectRoute(route)
            _castState.value = CastState.StartingPlayback(title, rendererName)
        }.onFailure {
            _castState.value = CastState.Error("Failed to connect Chromecast: ${it.message}")
        }
    }

    fun play() {
        remoteMediaClient?.play()
    }

    fun pause() {
        remoteMediaClient?.pause()
    }

    fun seek(positionMs: Long) {
        val client = remoteMediaClient ?: return
        val seekOptions = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        client.seek(seekOptions)
    }

    fun setVolume(level: Int) {
        val session = currentCastSession() ?: return
        val normalized = level.coerceIn(0, 100) / 100.0
        runCatching { session.volume = normalized }
    }

    fun toggleMute() {
        val session = currentCastSession() ?: return
        runCatching { session.setMute(!session.isMute) }
    }

    fun stop() {
        runCatching { remoteMediaClient?.stop() }
        pendingLoad = null
        _playbackInfo.value = CastPlaybackInfo()
        _castState.value = CastState.Idle
    }

    fun clearErrorState() {
        if (_castState.value is CastState.Error) {
            _castState.value = CastState.Idle
        }
    }

    fun release() {
        stopDiscovery()
        detachRemoteClient()
    }

    fun isGoogleCastRenderer(rendererUdn: String): Boolean {
        return rendererUdn.startsWith(GOOGLE_CAST_PREFIX)
    }

    private fun refreshRoutes() {
        val discovered = mediaRouter.routes
            .filter { route -> route.matchesSelector(routeSelector) && route.isEnabled }

        routes.clear()
        discovered.forEach { route -> routes[route.id] = route }

        if (selectedRouteId != null && routes[selectedRouteId] == null) {
            selectedRouteId = null
        }

        _renderers.value = discovered
            .map { route ->
                DlnaRenderer(
                    udn = "$GOOGLE_CAST_PREFIX${route.id}",
                    name = route.name?.toString()?.ifBlank { "Chromecast" } ?: "Chromecast",
                    model = "Google Cast"
                )
            }
            .sortedBy { it.name }

        if (_renderers.value.isNotEmpty() && _castState.value is CastState.Discovering) {
            _castState.value = CastState.Idle
        }
    }

    private fun resolveSelectedRoute(): MediaRouter.RouteInfo? {
        selectedRouteId?.let { currentId -> routes[currentId]?.let { return it } }
        val first = routes.values.firstOrNull() ?: return null
        selectedRouteId = first.id
        return first
    }

    private fun loadPendingIfAny(session: CastSession) {
        val pending = pendingLoad ?: return
        val client = session.remoteMediaClient
        if (client == null) {
            _castState.value = CastState.Error("Chromecast media client is unavailable")
            return
        }

        attachSession(session)

        val metadata = MediaMetadata(resolveMetadataType(pending.mimeType)).apply {
            putString(MediaMetadata.KEY_TITLE, pending.title)
        }

        val streamType = if (isLikelyLiveStream(pending.url, pending.mimeType)) {
            MediaInfo.STREAM_TYPE_LIVE
        } else {
            MediaInfo.STREAM_TYPE_BUFFERED
        }

        val mediaInfo = MediaInfo.Builder(pending.url)
            .setStreamType(streamType)
            .setContentType(pending.mimeType)
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        _castState.value = CastState.StartingPlayback(pending.title, pending.rendererName)

        client.load(request).setResultCallback { result ->
            if (result.status.isSuccess) {
                _castState.value = CastState.Casting(pending.title, pending.rendererName)
            } else {
                _castState.value = CastState.Error(
                    "Chromecast load failed (${result.status.statusCode})"
                )
            }
        }

        pendingLoad = null
    }

    private fun attachSession(session: CastSession?) {
        val client = session?.remoteMediaClient
        if (client === remoteMediaClient) {
            updatePlaybackInfoFromClient()
            return
        }

        detachRemoteClient()
        remoteMediaClient = client
        remoteMediaClient?.registerCallback(mediaClientCallback)
        updatePlaybackInfoFromClient()
    }

    private fun detachRemoteClient() {
        remoteMediaClient?.unregisterCallback(mediaClientCallback)
        remoteMediaClient = null
    }

    private fun updatePlaybackInfoFromClient() {
        val client = remoteMediaClient ?: return

        val positionMs = client.approximateStreamPosition.coerceAtLeast(0L)
        val durationMs = client.streamDuration.takeIf { it > 0L } ?: 0L

        _playbackInfo.value = _playbackInfo.value.copy(
            positionMs = positionMs,
            durationMs = durationMs
        )

        when (client.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING,
            MediaStatus.PLAYER_STATE_PAUSED,
            MediaStatus.PLAYER_STATE_BUFFERING -> {
                if (_castState.value is CastState.SendingUri || _castState.value is CastState.StartingPlayback) {
                    return
                }
                val title = client.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty()
                    .ifBlank { "Media" }
                val rendererName = currentCastSession()?.castDevice?.friendlyName
                    ?.ifBlank { "Chromecast" }
                    ?: "Chromecast"
                _castState.value = CastState.Casting(title, rendererName)
            }

            MediaStatus.PLAYER_STATE_IDLE -> {
                if (_castState.value !is CastState.Discovering && pendingLoad == null) {
                    _castState.value = CastState.Idle
                }
            }
        }
    }

    private fun currentCastSession(): CastSession? {
        return currentCastContext()?.sessionManager?.currentCastSession
    }

    private fun currentCastContext(): CastContext? {
        return runCatching { CastContext.getSharedInstance(appContext) }
            .onFailure { Log.w(TAG, "CastContext unavailable", it) }
            .getOrNull()
    }

    private fun resolveMetadataType(mimeType: String): Int {
        val normalized = mimeType.lowercase()
        return when {
            normalized.startsWith("image/") -> MediaMetadata.MEDIA_TYPE_PHOTO
            normalized.startsWith("audio/") -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            else -> MediaMetadata.MEDIA_TYPE_MOVIE
        }
    }

    private fun isLikelyLiveStream(url: String, mimeType: String): Boolean {
        val normalizedMime = mimeType.lowercase()
        val normalizedUrl = url.lowercase()
        return normalizedMime.contains("video/avc") ||
            normalizedMime.contains("application/vnd.apple.mpegurl") ||
            normalizedUrl.endsWith(".m3u8") ||
            normalizedUrl.endsWith(".h264")
    }

    private fun inferMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.endsWith(".h264") -> "video/avc"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }
}

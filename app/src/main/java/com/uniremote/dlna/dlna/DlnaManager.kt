package com.uniremote.dlna.dlna

import android.util.Log
import org.jupnp.android.AndroidUpnpService
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.avtransport.callback.GetPositionInfo
import org.jupnp.support.avtransport.callback.Pause
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.Seek
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.avtransport.callback.Stop
import org.jupnp.support.model.SeekMode
import org.jupnp.support.model.PositionInfo
import org.jupnp.support.renderingcontrol.callback.GetMute
import org.jupnp.support.renderingcontrol.callback.GetVolume
import org.jupnp.support.renderingcontrol.callback.SetMute
import org.jupnp.support.renderingcontrol.callback.SetVolume
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DlnaManager(
    private val onDevicesChanged: (List<DlnaRenderer>) -> Unit,
    private val onError: (String) -> Unit,
    private val onPosition: (PositionInfo) -> Unit,
    private val onVolume: (Int?, Boolean?) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "DlnaManager"
        private const val PLAY_AFTER_SET_URI_DELAY_MS = 180L
        private const val PLAY_RETRY_DELAY_MS = 250L
        private const val MAX_PLAY_RETRIES = 1
    }

    private var upnpService: AndroidUpnpService? = null
    private val renderers = linkedMapOf<String, RemoteDevice>()
    private val playScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DlnaPlayScheduler").apply { isDaemon = true }
    }

    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
            if (isRenderer(device)) {
                renderers[device.identity.udn.identifierString] = device
                dispatchDevices()
            }
        }

        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
            if (renderers.remove(device.identity.udn.identifierString) != null) {
                dispatchDevices()
            }
        }

    }

    fun bind(service: AndroidUpnpService) {
        upnpService = service
        // registry / controlPoint can be null if the service hasn't finished initialising yet.
        // Guard with ?.let so we don't crash; the caller can call refresh() once ready.
        service.registry?.addListener(registryListener)
            ?: run { upnpService = null; return }  // Service not ready — bail out
        service.controlPoint?.search()
    }

    fun unbind() {
        val service = upnpService ?: return
        runCatching { service.registry?.removeListener(registryListener) }
        upnpService = null
        renderers.clear()
        dispatchDevices()
    }

    fun refresh() {
        upnpService?.controlPoint?.search()
    }

    fun cast(
        rendererUdn: String,
        mediaUrl: String,
        title: String,
        onUriAccepted: () -> Unit = {},
        onPlaybackStarted: () -> Unit = {},
        onFailure: (String) -> Unit = { onError(it) }
    ) {
        val service = upnpService
            ?: return onFailure("DLNA service is not ready. Please scan again and retry.")
        val renderer = renderers[rendererUdn]
            ?: return onFailure("Renderer not available")

        val avTransport = findServiceByType(renderer, "AVTransport")
            ?: return onFailure("Renderer does not expose AVTransport")

        val metadata = didlMetadata(title, mediaUrl)
        setUriAndPlay(
            service = service,
            avTransport = avTransport,
            mediaUrl = mediaUrl,
            metadata = metadata,
            allowMetadataFallback = true,
            onUriAccepted = onUriAccepted,
            onPlaybackStarted = onPlaybackStarted,
            onFailure = onFailure
        )
    }

    fun stop(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn]
            ?: return
        val avTransport = findServiceByType(renderer, "AVTransport")
            ?: return

        service.controlPoint.execute(
            object : Stop(avTransport) {
                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Stop failed: $defaultMsg")
                }
            }
        )
    }

    fun play(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val avTransport = findServiceByType(renderer, "AVTransport") ?: return

        service.controlPoint.execute(
            object : Play(avTransport) {
                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Play failed: $defaultMsg")
                }
            }
        )
    }

    fun pause(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val avTransport = findServiceByType(renderer, "AVTransport") ?: return

        service.controlPoint.execute(
            object : Pause(avTransport) {
                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Pause failed: $defaultMsg")
                }
            }
        )
    }

    fun seekTo(rendererUdn: String, targetMs: Long) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val avTransport = findServiceByType(renderer, "AVTransport") ?: return
        val target = formatUpnpTime(targetMs)

        service.controlPoint.execute(
            object : Seek(avTransport, SeekMode.REL_TIME, target) {
                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Seek failed: $defaultMsg")
                }
            }
        )
    }

    fun fetchVolume(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val renderingControl = findServiceByType(renderer, "RenderingControl") ?: return

        service.controlPoint.execute(
            object : GetVolume(renderingControl) {
                override fun received(
                    invocation: ActionInvocation<out Service<*, *>>,
                    currentVolume: Int
                ) {
                    onVolume(currentVolume, null)
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Get volume failed: $defaultMsg")
                }
            }
        )

        service.controlPoint.execute(
            object : GetMute(renderingControl) {
                override fun received(
                    invocation: ActionInvocation<out Service<*, *>>,
                    currentMute: Boolean
                ) {
                    onVolume(null, currentMute)
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Get mute failed: $defaultMsg")
                }
            }
        )
    }

    fun setVolume(rendererUdn: String, level: Int) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val renderingControl = findServiceByType(renderer, "RenderingControl") ?: return
        val safeLevel = level.coerceIn(0, 100).toLong()

        service.controlPoint.execute(
            object : SetVolume(renderingControl, safeLevel) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                    onVolume(safeLevel.toInt(), null)
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Set volume failed: $defaultMsg")
                }
            }
        )
    }

    fun setMute(rendererUdn: String, muted: Boolean) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn] ?: return
        val renderingControl = findServiceByType(renderer, "RenderingControl") ?: return

        service.controlPoint.execute(
            object : SetMute(renderingControl, muted) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                    onVolume(null, muted)
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Set mute failed: $defaultMsg")
                }
            }
        )
    }

    fun fetchPosition(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn]
            ?: return
        val avTransport = findServiceByType(renderer, "AVTransport")
            ?: return

        service.controlPoint.execute(
            object : GetPositionInfo(avTransport) {
                override fun received(
                    invocation: ActionInvocation<out Service<*, *>>,
                    positionInfo: PositionInfo
                ) {
                    onPosition(positionInfo)
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Get position failed: $defaultMsg")
                }
            }
        )
    }

    private fun dispatchDevices() {
        val devices = renderers.values.map {
            DlnaRenderer(
                udn = it.identity.udn.identifierString,
                name = it.details.friendlyName ?: "DLNA Renderer",
                model = it.details.modelDetails?.modelName
            )
        }.sortedBy { it.name }

        onDevicesChanged(devices)
    }

    private fun isRenderer(device: RemoteDevice): Boolean {
        return supportsAvTransportCasting(device)
    }

    private fun supportsAvTransportCasting(device: RemoteDevice): Boolean {
        val avTransport = findServiceByType(device, "AVTransport") ?: return false
        return runCatching { avTransport.getAction("SetAVTransportURI") != null }.getOrDefault(false)
    }

    fun resolveLocalIpForRenderer(rendererUdn: String): String? {
        val renderer = renderers[rendererUdn] ?: return null
        val rendererHost = runCatching {
            renderer.identity.descriptorURL.host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(rendererHost), 9)
                (socket.localAddress as? Inet4Address)
                    ?.hostAddress
                    ?.lowercase(Locale.US)
            }
        }.getOrNull()
    }

    private fun findServiceByType(device: RemoteDevice, type: String): RemoteService? {
        val directMatch = device.findServices()
            .firstOrNull { it.serviceType.type.equals(type, ignoreCase = true) }
        if (directMatch is RemoteService) return directMatch

        return runCatching { device.findService(UDAServiceType(type)) }.getOrNull()
    }

    private fun setUriAndPlay(
        service: AndroidUpnpService,
        avTransport: RemoteService,
        mediaUrl: String,
        metadata: String,
        allowMetadataFallback: Boolean,
        setUriRetryCount: Int = 0,
        onUriAccepted: () -> Unit,
        onPlaybackStarted: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        service.controlPoint.execute(
            object : SetAVTransportURI(avTransport, mediaUrl, metadata) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                    Log.i(TAG, "SetAVTransportURI success uriRetry=$setUriRetryCount")
                    onUriAccepted()
                    schedulePlayAfterSetUri(
                        service = service,
                        avTransport = avTransport,
                        setUriRetryCount = setUriRetryCount,
                        playRetryCount = 0,
                        onPlaybackStarted = onPlaybackStarted,
                        onFailure = onFailure
                    )
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    if (allowMetadataFallback && metadata.isNotBlank()) {
                        Log.w(
                            TAG,
                            "SetAVTransportURI failed, retrying with empty metadata uriRetry=${setUriRetryCount + 1}: $defaultMsg"
                        )
                        setUriAndPlay(
                            service = service,
                            avTransport = avTransport,
                            mediaUrl = mediaUrl,
                            metadata = "",
                            allowMetadataFallback = false,
                            setUriRetryCount = setUriRetryCount + 1,
                            onUriAccepted = onUriAccepted,
                            onPlaybackStarted = onPlaybackStarted,
                            onFailure = onFailure
                        )
                    } else {
                        Log.w(TAG, "SetAVTransportURI failed uriRetry=$setUriRetryCount: $defaultMsg")
                        onFailure("Set URI failed: $defaultMsg")
                    }
                }
            }
        )
    }

    private fun schedulePlayAfterSetUri(
        service: AndroidUpnpService,
        avTransport: RemoteService,
        setUriRetryCount: Int,
        playRetryCount: Int,
        onPlaybackStarted: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val delayMs = if (playRetryCount == 0) {
            PLAY_AFTER_SET_URI_DELAY_MS
        } else {
            PLAY_RETRY_DELAY_MS
        }

        playScheduler.schedule(
            {
                val current = upnpService
                if (current == null || current !== service) {
                    return@schedule
                }

                executePlay(
                    service = service,
                    avTransport = avTransport,
                    onPlaybackStarted = {
                        Log.i(TAG, "Play success uriRetry=$setUriRetryCount playRetry=$playRetryCount")
                        onPlaybackStarted()
                    },
                    onFailure = { message ->
                        if (playRetryCount < MAX_PLAY_RETRIES) {
                            Log.w(
                                TAG,
                                "Play failed, scheduling retry uriRetry=$setUriRetryCount playRetry=${playRetryCount + 1}: $message"
                            )
                            schedulePlayAfterSetUri(
                                service = service,
                                avTransport = avTransport,
                                setUriRetryCount = setUriRetryCount,
                                playRetryCount = playRetryCount + 1,
                                onPlaybackStarted = onPlaybackStarted,
                                onFailure = onFailure
                            )
                        } else {
                            Log.w(TAG, "Play failed uriRetry=$setUriRetryCount playRetry=$playRetryCount: $message")
                            onFailure(message)
                        }
                    }
                )
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun executePlay(
        service: AndroidUpnpService,
        avTransport: RemoteService,
        onPlaybackStarted: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        service.controlPoint.execute(
            object : Play(avTransport) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                    onPlaybackStarted()
                }

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onFailure("Play failed: $defaultMsg")
                }
            }
        )
    }

    private fun didlMetadata(title: String, mediaUrl: String): String {
        return """
            <DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">
              <item id=\"0\" parentID=\"0\" restricted=\"1\">
                <dc:title>${escapeXml(title)}</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo=\"http-get:*:*:*\">${escapeXml(mediaUrl)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatUpnpTime(targetMs: Long): String {
        val totalSeconds = (targetMs.coerceAtLeast(0L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}

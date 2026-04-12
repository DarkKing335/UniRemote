package com.uniremote.dlna.dlna

import org.jupnp.android.AndroidUpnpService
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.meta.Service
import org.jupnp.model.types.ServiceType
import org.jupnp.model.types.UDADeviceType
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

class DlnaManager(
    private val onDevicesChanged: (List<DlnaRenderer>) -> Unit,
    private val onError: (String) -> Unit,
    private val onPosition: (PositionInfo) -> Unit,
    private val onVolume: (Int?, Boolean?) -> Unit = { _, _ -> }
) {

    private var upnpService: AndroidUpnpService? = null
    private val renderers = linkedMapOf<String, RemoteDevice>()

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
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn]
            ?: return onFailure("Renderer not available")

        val avTransport = findService(renderer, UDAServiceType("AVTransport"))
            ?: return onFailure("Renderer does not expose AVTransport")

        val metadata = didlMetadata(title, mediaUrl)

        service.controlPoint.execute(
            object : SetAVTransportURI(avTransport, mediaUrl, metadata) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                    onUriAccepted()
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

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onFailure("Set URI failed: $defaultMsg")
                }
            }
        )
    }

    fun stop(rendererUdn: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn]
            ?: return
        val avTransport = findService(renderer, UDAServiceType("AVTransport"))
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
        val avTransport = findService(renderer, UDAServiceType("AVTransport")) ?: return

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
        val avTransport = findService(renderer, UDAServiceType("AVTransport")) ?: return

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
        val avTransport = findService(renderer, UDAServiceType("AVTransport")) ?: return
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
        val renderingControl = findService(renderer, UDAServiceType("RenderingControl")) ?: return

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
        val renderingControl = findService(renderer, UDAServiceType("RenderingControl")) ?: return
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
        val renderingControl = findService(renderer, UDAServiceType("RenderingControl")) ?: return

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
        val avTransport = findService(renderer, UDAServiceType("AVTransport"))
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
        return device.type == UDADeviceType("MediaRenderer") ||
            device.findServices().any { it.serviceType.type == "AVTransport" }
    }

    private fun findService(device: RemoteDevice, serviceType: ServiceType): RemoteService? {
        return device.findService(serviceType)
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

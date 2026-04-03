package com.uniremote.dlna.dlna

import org.jupnp.android.AndroidUpnpService
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.meta.Service
import org.jupnp.model.types.ServiceType
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.avtransport.callback.GetPositionInfo
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.avtransport.callback.Stop
import org.jupnp.support.model.PositionInfo

class DlnaManager(
    private val onDevicesChanged: (List<DlnaRenderer>) -> Unit,
    private val onError: (String) -> Unit,
    private val onPosition: (PositionInfo) -> Unit
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

        override fun localDeviceAdded(registry: Registry, device: Device<*, *, *>) {
            if (device is RemoteDevice && isRenderer(device)) {
                renderers[device.identity.udn.identifierString] = device
                dispatchDevices()
            }
        }
    }

    fun bind(service: AndroidUpnpService) {
        upnpService = service
        service.registry.addListener(registryListener)
        service.controlPoint.search()
    }

    fun unbind() {
        val service = upnpService ?: return
        service.registry.removeListener(registryListener)
        upnpService = null
        renderers.clear()
        dispatchDevices()
    }

    fun refresh() {
        upnpService?.controlPoint?.search()
    }

    fun cast(rendererUdn: String, mediaUrl: String, title: String) {
        val service = upnpService ?: return
        val renderer = renderers[rendererUdn]
            ?: return onError("Renderer not available")

        val avTransport = findService(renderer, UDAServiceType("AVTransport"))
            ?: return onError("Renderer does not expose AVTransport")

        val metadata = didlMetadata(title, mediaUrl)

        service.controlPoint.execute(
            object : SetAVTransportURI(avTransport, mediaUrl, metadata) {
                override fun success(invocation: ActionInvocation<out Service<*, *>>) {
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

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>,
                    operation: org.jupnp.model.message.UpnpResponse,
                    defaultMsg: String
                ) {
                    onError("Set URI failed: $defaultMsg")
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
}

package com.example.uniremote.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.util.DeviceIdUtil
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "DeviceDiscovery"

/**
 * Discovers TV devices on the local network using Android NSD (mDNS).
 *
 * Each brand registers a specific mDNS service type:
 *  - Samsung: _samsungsmarthome._tcp  (or older: _samsung-remote._tcp)
 *  - LG WebOS: _webostv._tcp
 *  - Android TV: _androidtvremote._tcp  and  _adb-tls-connect._tcp
 *
 * ✅ Uses DeviceIdUtil.stableId() for stable, MAC-preferred device IDs.
 * ✅ Does NOT attach SSID – that is DeviceRepository's responsibility.
 */
class DeviceDiscovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val serviceTypes = listOf(
        "_samsungsmarthome._tcp" to TvBrand.SAMSUNG,
        "_samsung-remote._tcp"   to TvBrand.SAMSUNG,
        "_webostv._tcp"          to TvBrand.LG,
        "_androidtvremote._tcp"  to TvBrand.ANDROID,
        "_adb-tls-connect._tcp"  to TvBrand.ANDROID,
        "_roku-epp._tcp"         to TvBrand.ROKU,
        "_vizio._tcp"            to TvBrand.VIZIO,
        "_panasonic-viera._tcp"  to TvBrand.PANASONIC,
        "_googletv._tcp"         to TvBrand.ANDROID,
        "_vidaa._tcp"            to TvBrand.HISENSE,
        "_googlecast._tcp"       to TvBrand.ANDROID, // Support Android TV/Chromecast
        "_airplay._tcp"          to TvBrand.UNKNOWN // AirPlay can be on many brands
    )

    private fun detectBrand(serviceName: String, baseBrand: TvBrand): TvBrand {
        val name = serviceName.uppercase()
        return when {
            name.contains("SONY") || name.contains("BRAVIA") -> TvBrand.SONY
            name.contains("TCL") -> TvBrand.TCL
            name.contains("XIAOMI") || name.contains("MI TV") -> TvBrand.XIAOMI
            name.contains("HISENSE") || name.contains("VIDAA") -> TvBrand.HISENSE
            name.contains("TOSHIBA") -> TvBrand.TOSHIBA
            name.contains("SHARP") || name.contains("AQUOS") -> TvBrand.SHARP
            name.contains("PHILIPS") -> TvBrand.PHILIPS
            name.contains("SAMSUNG") -> TvBrand.SAMSUNG
            name.contains("LG") || name.contains("WEBOS") -> TvBrand.LG
            name.contains("PANASONIC") || name.contains("VIERA") -> TvBrand.PANASONIC
            else -> baseBrand
        }
    }

    /**
     * Returns a cold Flow emitting the current discovered device list
     * whenever a new device is found or lost.
     * Cancel the collecting coroutine to stop discovery cleanly.
     */
    fun discover(): Flow<List<TvDevice>> = callbackFlow {
        val discovered      = mutableMapOf<String, TvDevice>()
        val listeners       = mutableListOf<NsdManager.DiscoveryListener>()
        val startedListeners = mutableSetOf<NsdManager.DiscoveryListener>()

        fun emit() = trySend(discovered.values.toList())

        for ((serviceType, brand) in serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "Start discovery failed: $type code=$code")
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "Stop discovery failed: $type code=$code")
                }
                override fun onDiscoveryStarted(type: String) {
                    Log.d(TAG, "Discovery started: $type")
                    startedListeners.add(this)
                }
                override fun onDiscoveryStopped(type: String) {
                    Log.d(TAG, "Discovery stopped: $type")
                    startedListeners.remove(this)
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    // Remove by any matching IP (id not yet resolved)
                    discovered.entries.removeIf { it.value.name == info.serviceName }
                    emit()
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(svcInfo: NsdServiceInfo, code: Int) {
                            Log.w(TAG, "Resolve failed: ${svcInfo.serviceName} code=$code")
                        }
                        override fun onServiceResolved(svcInfo: NsdServiceInfo) {
                            val ip   = svcInfo.host?.hostAddress ?: return
                            val port = if (svcInfo.port > 0) svcInfo.port else brand.defaultPort
                            val name   = svcInfo.serviceName ?: ip
                            val brandResolved = detectBrand(name, brand)
                            // ✅ Stable ID: prefer MAC (from NSD attributes if available), else hash
                            val mac    = svcInfo.attributes["mac"]
                                ?.let { String(it) } ?: ""
                            val id     = DeviceIdUtil.stableId(mac = mac, ip = ip, name = name)
                            val device = TvDevice(
                                id    = id,
                                name  = name,
                                brand = brandResolved,
                                ip    = ip,
                                mac   = mac,
                                port  = port
                                // ssid intentionally NOT set here – Repository attaches it on save
                            )
                            discovered[id] = device
                            emit()
                        }
                    })
                }
            }
            listeners.add(listener)
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start discovery for $serviceType", e)
            }
        }

        awaitClose {
            listeners.forEach { listener ->
                if (!startedListeners.contains(listener)) return@forEach
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Listener not registered or already stopped", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error while stopping NSD", e)
                }
            }
        }
    }
}

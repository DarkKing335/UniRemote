package com.example.uniremote.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
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
 * Usage:
 *   DeviceDiscovery(context).discover().collect { devices -> … }
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
    )

    /**
     * Returns a cold Flow that emits the current discovered device list
     * whenever a new device is found or lost.
     * Call [Flow.collect] to start discovery; cancel the coroutine to stop.
     */
    fun discover(): Flow<List<TvDevice>> = callbackFlow {
        val discovered = mutableMapOf<String, TvDevice>()
        val listeners  = mutableListOf<NsdManager.DiscoveryListener>()

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
                }
                override fun onDiscoveryStopped(type: String) {
                    Log.d(TAG, "Discovery stopped: $type")
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    discovered.remove(info.serviceName)
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
                            val name = svcInfo.serviceName ?: ip
                            val id   = "${brand.name}_$ip"
                            val device = TvDevice(
                                id    = id,
                                name  = name,
                                brand = brand,
                                ip    = ip,
                                port  = port
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
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
        }
    }
}

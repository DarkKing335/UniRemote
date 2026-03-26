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
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "DeviceDiscovery"

/**
 * Discovers TV devices on the local network using Android NSD (mDNS).
 *
 * Supported brands and their mDNS service types:
 *  - Samsung : _samsungsmarthome._tcp, _samsung-remote._tcp
 *  - LG WebOS: _webostv._tcp
 *  - Sony     : _androidtvremote2._tcp (primary), _sony-sdcp._tcp, _dial._tcp
 *  - Android  : _androidtvremote._tcp, _adb-tls-connect._tcp
 *
 * ⚠️ NsdManager can only resolve one service at a time.
 *    A serial queue is used to avoid the "already resolving" crash.
 *
 * ✅ Uses DeviceIdUtil.stableId() for stable, MAC-preferred device IDs.
 * ✅ Does NOT attach SSID – that is DeviceRepository's responsibility.
 */
class DeviceDiscovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val serviceTypes = listOf(
        // Samsung
        "_samsungsmarthome._tcp" to TvBrand.SAMSUNG,
        "_samsung-remote._tcp"   to TvBrand.SAMSUNG,
        // LG
        "_webostv._tcp"          to TvBrand.LG,
        // Google TV Remote v2 (Standard API on port 6466/6467)
        "_androidtvremote2._tcp" to TvBrand.GOOGLE_TV,
        // Sony proprietary or DIAL
        "_sony-sdcp._tcp"        to TvBrand.SONY,
        "_dial._tcp"             to TvBrand.SONY,
        // Generic Android TV
        "_androidtvremote._tcp"  to TvBrand.ANDROID,
        "_adb-tls-connect._tcp"  to TvBrand.ANDROID,
    )

    /**
     * Returns a cold Flow emitting the current discovered device list
     * whenever a new device is found or lost.
     * Cancel the collecting coroutine to stop discovery cleanly.
     */
    fun discover(): Flow<List<TvDevice>> = callbackFlow {
        val discovered       = mutableMapOf<String, TvDevice>()
        val listeners        = mutableListOf<NsdManager.DiscoveryListener>()
        val startedListeners = mutableSetOf<NsdManager.DiscoveryListener>()

        // ── Serial resolve queue ──────────────────────────────────────────────
        // NsdManager only allows ONE active resolve at a time.
        // Violations cause "listener already in use" IllegalArgumentException.
        val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
        val isResolving = java.util.concurrent.atomic.AtomicBoolean(false)

        fun processResolveQueue(brand: TvBrand) {
            if (isResolving.get()) return
            val info = resolveQueue.poll() ?: return
            isResolving.set(true)
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(svcInfo: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "Resolve failed: ${svcInfo.serviceName} code=$code")
                    isResolving.set(false)
                    processResolveQueue(brand)
                }
                override fun onServiceResolved(svcInfo: NsdServiceInfo) {
                    isResolving.set(false)
                    val ip   = svcInfo.host?.hostAddress ?: run {
                        processResolveQueue(brand)
                        return
                    }
                    val port = if (svcInfo.port > 0) svcInfo.port else brand.defaultPort
                    val name = svcInfo.serviceName ?: ip
                    val mac  = svcInfo.attributes["mac"]
                        ?.let { String(it) } ?: ""
                    val id   = DeviceIdUtil.stableId(mac = mac, ip = ip, name = name)

                    // Detect Sony more precisely
                    val detectedBrand = when {
                        brand == TvBrand.GOOGLE_TV -> brand // Force Google TV protocol
                        name.contains("sony",   ignoreCase = true) -> TvBrand.SONY
                        name.contains("bravia", ignoreCase = true) -> TvBrand.SONY
                        else -> brand
                    }

                    val device = TvDevice(
                        id    = id,
                        name  = name,
                        brand = detectedBrand,
                        ip    = ip,
                        mac   = mac,
                        port  = port
                        // ssid intentionally NOT set here – Repository attaches it on save
                    )
                    discovered[id] = device
                    trySend(discovered.values.toList())
                    processResolveQueue(detectedBrand)
                }
            })
        }

        // ── Build one discovery listener per service type ─────────────────────
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
                    discovered.entries.removeIf { it.value.name == info.serviceName }
                    trySend(discovered.values.toList())
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${info.serviceName} [$serviceType]")
                    resolveQueue.offer(info)
                    processResolveQueue(brand)
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

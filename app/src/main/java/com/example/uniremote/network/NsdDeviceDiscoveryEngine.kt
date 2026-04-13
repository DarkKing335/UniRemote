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

internal class NsdDeviceDiscoveryEngine(
    context: Context,
    private val tag: String,
    private val serviceTypes: List<Pair<String, TvBrand>>
) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun discover(): Flow<List<TvDevice>> = callbackFlow {
        val discovered = java.util.concurrent.ConcurrentHashMap<String, TvDevice>()
        val serviceIndex = java.util.concurrent.ConcurrentHashMap<String, String>()
        val canonicalIndex = java.util.concurrent.ConcurrentHashMap<String, String>()
        val serviceRefCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val discoveryScore = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val startedListeners = mutableSetOf<NsdManager.DiscoveryListener>()

        fun serviceIdentity(serviceType: String?, serviceName: String?): String {
            return "${serviceType ?: "unknown"}|${serviceName ?: ""}"
        }

        fun canonicalIdentity(ip: String): String = ip

        fun readableNameQuality(name: String): Int {
            val trimmed = name.trim()
            var score = 100
            if (trimmed.length > 28) score -= 20
            if (Regex("[0-9a-fA-F]{12,}").containsMatchIn(trimmed)) score -= 40
            if (trimmed.contains("_")) score -= 8
            if (trimmed.contains("-")) score -= 5
            return score
        }

        fun candidateScore(serviceType: String, brand: TvBrand, port: Int): Int {
            val base = when {
                serviceType.contains("androidtvremote2", ignoreCase = true) -> 120
                serviceType.contains("adb-tls-connect", ignoreCase = true) -> 110
                serviceType.contains("samsungmsf", ignoreCase = true) -> 100
                serviceType.contains("samsung-remote", ignoreCase = true) -> 95
                serviceType.contains("webostv", ignoreCase = true) -> 95
                serviceType.contains("lgsmarttv", ignoreCase = true) -> 90
                serviceType.contains("sony-ircc", ignoreCase = true) -> 85
                serviceType.contains("androidtvremote", ignoreCase = true) -> 80
                serviceType.contains("googlecast", ignoreCase = true) -> 60
                serviceType.contains("dial", ignoreCase = true) -> 50
                else -> 40
            }
            val portBonus = when (brand) {
                TvBrand.GOOGLE_TV, TvBrand.ANDROID, TvBrand.XIAOMI -> if (port == 6466 || port == 6467) 12 else 0
                TvBrand.SAMSUNG -> if (port == 8002 || port == 8001) 10 else 0
                TvBrand.LG -> if (port == 3000 || port == 3001) 10 else 0
                TvBrand.ROKU -> if (port == 8060) 10 else 0
                else -> 0
            }
            return base + portBonus
        }

        fun incServiceRef(deviceId: String) {
            serviceRefCount[deviceId] = (serviceRefCount[deviceId] ?: 0) + 1
        }

        fun decServiceRef(deviceId: String) {
            val next = (serviceRefCount[deviceId] ?: 1) - 1
            if (next <= 0) {
                serviceRefCount.remove(deviceId)
                val removed = discovered.remove(deviceId)
                if (removed != null) {
                    canonicalIndex.remove(canonicalIdentity(removed.ip), deviceId)
                }
                discoveryScore.remove(deviceId)
            } else {
                serviceRefCount[deviceId] = next
            }
        }

        data class ResolveTask(val info: NsdServiceInfo, val brand: TvBrand)
        val resolveQueue = LinkedBlockingQueue<ResolveTask>()
        val isResolving = java.util.concurrent.atomic.AtomicBoolean(false)

        fun processResolveQueue() {
            if (!isResolving.compareAndSet(false, true)) return
            val task = resolveQueue.poll()
            if (task == null) {
                isResolving.set(false)
                return
            }

            val brand = task.brand
            val info = task.info

            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(svcInfo: NsdServiceInfo, code: Int) {
                    Log.w(tag, "Resolve failed: ${svcInfo.serviceName} code=$code")
                    isResolving.set(false)
                    processResolveQueue()
                }

                override fun onServiceResolved(svcInfo: NsdServiceInfo) {
                    isResolving.set(false)
                    val ip = svcInfo.host?.hostAddress ?: run {
                        processResolveQueue()
                        return
                    }
                    val port = if (svcInfo.port > 0) svcInfo.port else brand.defaultPort
                    val name = svcInfo.serviceName ?: ip
                    val mac = svcInfo.attributes["mac"]?.let { String(it) } ?: ""
                    val provisionalId = DeviceIdUtil.stableId(mac = mac, ip = ip, name = name)
                    val serviceType = svcInfo.serviceType ?: task.info.serviceType ?: "unknown"
                    val identity = serviceIdentity(serviceType, svcInfo.serviceName ?: task.info.serviceName)

                    val detectedBrand = when {
                        brand == TvBrand.GOOGLE_TV -> brand
                        name.contains("sony", ignoreCase = true) -> TvBrand.SONY
                        name.contains("bravia", ignoreCase = true) -> TvBrand.SONY
                        name.contains("xiaomi", ignoreCase = true) -> TvBrand.XIAOMI
                        name.contains("mi tv", ignoreCase = true) -> TvBrand.XIAOMI
                        name.contains("fire", ignoreCase = true) -> TvBrand.FIRE_TV
                        name.contains("amazon", ignoreCase = true) -> TvBrand.FIRE_TV
                        else -> brand
                    }

                    val canonicalKey = canonicalIdentity(ip)
                    val id = canonicalIndex.putIfAbsent(canonicalKey, provisionalId) ?: provisionalId

                    val device = TvDevice(
                        id = id,
                        name = name,
                        brand = detectedBrand,
                        ip = ip,
                        mac = mac,
                        port = port
                    )

                    val score = candidateScore(serviceType, detectedBrand, port)
                    val previousId = serviceIndex.put(identity, id)
                    if (previousId == null) {
                        incServiceRef(id)
                    } else if (previousId != id) {
                        decServiceRef(previousId)
                        incServiceRef(id)
                    }

                    val previousScore = discoveryScore[id] ?: Int.MIN_VALUE
                    val existing = discovered[id]
                    val shouldReplace = when {
                        existing == null -> true
                        score > previousScore -> true
                        score == previousScore && readableNameQuality(device.name) > readableNameQuality(existing.name) -> true
                        else -> false
                    }
                    if (shouldReplace) {
                        discovered[id] = device.copy(id = id)
                        discoveryScore[id] = score
                    }

                    trySend(discovered.values.toList())
                    processResolveQueue()
                }
            })
        }

        for ((serviceType, brand) in serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.w(tag, "Start discovery failed: $type code=$code")
                }

                override fun onStopDiscoveryFailed(type: String, code: Int) {
                    Log.w(tag, "Stop discovery failed: $type code=$code")
                }

                override fun onDiscoveryStarted(type: String) {
                    Log.d(tag, "Discovery started: $type")
                    startedListeners.add(this)
                }

                override fun onDiscoveryStopped(type: String) {
                    Log.d(tag, "Discovery stopped: $type")
                    startedListeners.remove(this)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    val identity = serviceIdentity(info.serviceType, info.serviceName)
                    val id = serviceIndex.remove(identity)
                    if (id != null) {
                        decServiceRef(id)
                    }
                    trySend(discovered.values.toList())
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    Log.d(tag, "Service found: ${info.serviceName} [$serviceType]")
                    resolveQueue.offer(ResolveTask(info, brand))
                    processResolveQueue()
                }
            }
            listeners.add(listener)
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(tag, "Could not start discovery for $serviceType", e)
            }
        }

        awaitClose {
            listeners.forEach { listener ->
                if (!startedListeners.contains(listener)) return@forEach
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    Log.w(tag, "Listener not registered or already stopped", e)
                } catch (e: Exception) {
                    Log.e(tag, "Unexpected error while stopping NSD", e)
                }
            }
        }
    }
}

package com.example.uniremote.network

import android.content.Context
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

private const val TAG = "RemoteControlDiscovery"

/**
 * Discovery domain for controllable TV targets only.
 *
 * Intentionally excludes `_googlecast._tcp` to avoid classifying Chromecast
 * endpoints as remote-control devices.
 */
class RemoteControlDiscovery(context: Context) {

    private val engine = NsdDeviceDiscoveryEngine(
        context = context,
        tag = TAG,
        serviceTypes = listOf(
            // Samsung
            "_samsungmsf._tcp" to TvBrand.SAMSUNG,
            "_samsungsmarthome._tcp" to TvBrand.SAMSUNG,
            "_samsung-remote._tcp" to TvBrand.SAMSUNG,
            // LG
            "_webostv._tcp" to TvBrand.LG,
            "_lgsmarttv._tcp" to TvBrand.LG,
            // Google TV Remote v2 (control protocol)
            "_androidtvremote2._tcp" to TvBrand.GOOGLE_TV,
            "_androidtv._tcp" to TvBrand.GOOGLE_TV,
            // Sony legacy
            "_sony-ircc._tcp" to TvBrand.SONY,
            "_sony-sdcp._tcp" to TvBrand.SONY,
            "_dial._tcp" to TvBrand.SONY,
            // Generic Android TV fallback
            "_androidtvremote._tcp" to TvBrand.ANDROID,
            "_adb-tls-connect._tcp" to TvBrand.ANDROID,
        )
    )

    private val rokuSsdpDiscovery = RokuSsdpDiscovery(context)

    fun discover(): Flow<List<TvDevice>> {
        return combine(
            engine.discover().onStart { emit(emptyList()) },
            rokuSsdpDiscovery.discover().onStart { emit(emptyList()) }
        ) { nsdDevices, rokuDevices ->
            mergeDevicesByIp(nsdDevices, rokuDevices)
        }
    }

    private fun mergeDevicesByIp(
        nsdDevices: List<TvDevice>,
        rokuDevices: List<TvDevice>
    ): List<TvDevice> {
        val mergedByIp = linkedMapOf<String, TvDevice>()

        // Keep NSD devices first to preserve existing ordering behavior.
        nsdDevices.forEach { device ->
            mergedByIp[device.ip] = device
        }

        // Roku SSDP should win for Roku endpoints because it has protocol-specific identity.
        rokuDevices.forEach { roku ->
            val existing = mergedByIp[roku.ip]
            mergedByIp[roku.ip] = when {
                existing == null -> roku
                existing.brand != TvBrand.ROKU -> roku
                roku.name.length > existing.name.length -> roku
                else -> existing
            }
        }

        return mergedByIp.values.toList()
    }
}

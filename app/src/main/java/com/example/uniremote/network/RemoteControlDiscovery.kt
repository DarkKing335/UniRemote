package com.example.uniremote.network

import android.content.Context
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.flow.Flow

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

    fun discover(): Flow<List<TvDevice>> = engine.discover()
}

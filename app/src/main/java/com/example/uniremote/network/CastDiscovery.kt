package com.example.uniremote.network

import android.content.Context
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.flow.Flow

private const val TAG = "CastDiscovery"

/**
 * Discovery domain for Cast endpoints (Chromecast).
 *
 * This list is separate from remote-control discovery so Cast-only devices are
 * never treated as TV remote-control targets.
 */
class CastDiscovery(context: Context) {

    private val engine = NsdDeviceDiscoveryEngine(
        context = context,
        tag = TAG,
        serviceTypes = listOf(
            "_googlecast._tcp" to TvBrand.GOOGLE_TV
        )
    )

    fun discover(): Flow<List<TvDevice>> = engine.discover()
}

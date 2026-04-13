package com.example.uniremote.network

import android.content.Context
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.flow.Flow

/**
 * Backward-compatible wrapper for remote-control discovery.
 *
 * Prefer [RemoteControlDiscovery] for TV control targets and [CastDiscovery]
 * for cast targets to keep discovery domains separated.
 */
@Deprecated(
    message = "Use RemoteControlDiscovery for remote targets and CastDiscovery for cast targets"
)
class DeviceDiscovery(private val context: Context) {
    private val delegate = RemoteControlDiscovery(context)

    fun discover(): Flow<List<TvDevice>> = delegate.discover()
}

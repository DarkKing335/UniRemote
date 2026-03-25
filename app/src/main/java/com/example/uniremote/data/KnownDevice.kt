package com.example.uniremote.data

/**
 * Represents a previously connected (known) TV device stored in DataStore.
 *
 * Separate from [TvDevice] so the domain model stays clean.
 */
data class KnownDevice(
    val id:              String,
    val name:            String,
    val brand:           String,         // TvBrand.name
    val ip:              String,
    val mac:             String,
    val port:            Int,
    val ssid:            String,         // WiFi SSID at last successful connect
    val lastConnectedMs: Long,           // epoch ms of last successful connection
    val lastSeenMs:      Long = 0L,      // epoch ms of last NSD scan detection
    val isOnline:        Boolean = false
)

// ── Mapping ───────────────────────────────────────────────────────────────────

fun KnownDevice.toDomain(): TvDevice = TvDevice(
    id              = id,
    name            = name,
    brand           = runCatching { TvBrand.valueOf(brand) }.getOrElse { TvBrand.UNKNOWN },
    ip              = ip,
    mac             = mac,
    port            = port,
    ssid            = ssid,
    lastConnectedMs = lastConnectedMs
)

fun TvDevice.toKnownDevice(ssid: String, nowMs: Long, isOnline: Boolean = true) = KnownDevice(
    id              = id,
    name            = name,
    brand           = brand.name,
    ip              = ip,
    mac             = mac,
    port            = port,
    ssid            = ssid,
    lastConnectedMs = nowMs,
    lastSeenMs      = nowMs,
    isOnline        = isOnline
)

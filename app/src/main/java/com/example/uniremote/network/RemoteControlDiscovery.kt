package com.example.uniremote.network

import android.content.Context
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

private const val TAG = "RemoteControlDiscovery"

/**
 * Combines all discovery sources into a single device list Flow:
 *   - NSD/mDNS: Samsung, Google TV, Sony, generic Android TV
 *   - Roku SSDP: Roku TVs (roku:ecp)
 *   - LG SSDP: LG WebOS TVs (urn:lge-com:service:webos-second-screen:1)
 *
 * LG TVs are intentionally excluded from NSD because LG advertises via SSDP/UPnP,
 * not mDNS — the NSD engine reliably misses them on most Android networks.
 * The dedicated LgSsdpDiscovery handles LG with ST=urn:lge-com:service:webos-second-screen:1
 * per the APK reverse-engineered WebOSTVService.discoveryFilter().
 */
class RemoteControlDiscovery(context: Context) {

    private val engine = NsdDeviceDiscoveryEngine(
        context = context,
        tag = TAG,
        serviceTypes = listOf(
            // Samsung (mDNS — Samsung advertises Multiscreen + SmartHome services)
            "_samsungmsf._tcp"        to TvBrand.SAMSUNG,
            "_samsungsmarthome._tcp"  to TvBrand.SAMSUNG,
            "_samsung-remote._tcp"    to TvBrand.SAMSUNG,
            // Google TV Remote v2 (certifiable control protocol)
            "_androidtvremote2._tcp"  to TvBrand.GOOGLE_TV,
            "_androidtv._tcp"         to TvBrand.GOOGLE_TV,
            // Sony legacy
            "_sony-ircc._tcp"         to TvBrand.SONY,
            "_sony-sdcp._tcp"         to TvBrand.SONY,
            "_dial._tcp"              to TvBrand.SONY,
            // Generic Android TV fallback
            "_androidtvremote._tcp"   to TvBrand.ANDROID,
            "_adb-tls-connect._tcp"   to TvBrand.ANDROID,
            // LG mDNS fallback — for newer LG TVs that happen to also advertise mDNS.
            // Primary LG discovery is handled by LgSsdpDiscovery (SSDP).
            "_webostv._tcp"           to TvBrand.LG,
            "_lgsmarttv._tcp"         to TvBrand.LG,
        )
    )

    private val rokuSsdpDiscovery = RokuSsdpDiscovery(context)
    private val lgSsdpDiscovery   = LgSsdpDiscovery(context)

    fun discover(): Flow<List<TvDevice>> {
        return combine(
            engine.discover().onStart        { emit(emptyList()) },
            rokuSsdpDiscovery.discover().onStart { emit(emptyList()) },
            lgSsdpDiscovery.discover().onStart   { emit(emptyList()) }
        ) { nsdDevices, rokuDevices, lgDevices ->
            mergeAll(nsdDevices, rokuDevices, lgDevices)
        }
    }

    // ── Merge logic ───────────────────────────────────────────────────────────

    private fun mergeAll(
        nsdDevices:  List<TvDevice>,
        rokuDevices: List<TvDevice>,
        lgDevices:   List<TvDevice>
    ): List<TvDevice> {
        val mergedByIp = linkedMapOf<String, TvDevice>()

        // 1. NSD devices as the baseline
        nsdDevices.forEach { mergedByIp[it.ip] = it }

        // 2. Roku SSDP wins for Roku endpoints (has protocol-specific identity)
        rokuDevices.forEach { roku ->
            val existing = mergedByIp[roku.ip]
            mergedByIp[roku.ip] = when {
                existing == null              -> roku
                existing.brand != TvBrand.ROKU -> roku
                roku.name.length > existing.name.length -> roku
                else -> existing
            }
        }

        // 3. LG SSDP wins for LG endpoints.
        //    If NSD already found the same IP as LG, SSDP result is preferred because
        //    it comes from the authoritative SSAP protocol filter.
        lgDevices.forEach { lg ->
            val existing = mergedByIp[lg.ip]
            mergedByIp[lg.ip] = when {
                existing == null            -> lg
                existing.brand != TvBrand.LG -> lg   // SSDP-confirmed LG replaces mDNS guess
                lg.name.length > existing.name.length -> lg
                else -> existing
            }
        }

        return mergedByIp.values.toList()
    }
}

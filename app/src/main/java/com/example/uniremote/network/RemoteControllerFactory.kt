package com.example.uniremote.network

import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.TvBrand

/**
 * Factory to create the appropriate [TvController] for a given [TvDevice].
 */
object RemoteControllerFactory {
    fun create(device: TvDevice): TvController {
        return when (device.brand) {
            TvBrand.SAMSUNG   -> SamsungTvController(device)
            TvBrand.LG        -> LgWebOsController(device)
            TvBrand.ROKU      -> RokuController(device)
            TvBrand.PANASONIC -> PanasonicTvController(device)
            TvBrand.VIZIO     -> VizioTvController(device)
            TvBrand.HISENSE   -> {
                // Hisense can be Android or VIDAA
                if (device.port == 8008 || device.port == 6466 || device.port == 6467) {
                    AndroidTvController(device)
                } else {
                    HisenseTvController(device)
                }
            }
            TvBrand.SONY, 
            TvBrand.TCL, 
            TvBrand.XIAOMI,
            TvBrand.ANDROID   -> AndroidTvController(device)
            else              -> AndroidTvController(device) // Default fallback
        }
    }
}

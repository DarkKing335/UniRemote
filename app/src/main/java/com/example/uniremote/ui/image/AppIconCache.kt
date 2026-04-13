package com.example.uniremote.ui.image

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import java.io.File

/**
 * Dedicated icon cache for TV app logos.
 * Keeps icons warm across Apps screen re-open for smoother UX.
 */
object AppIconCache {

    private const val DISK_CACHE_DIR = "tv_app_icon_cache"
    private const val DISK_CACHE_MAX_BYTES = 50L * 1024L * 1024L

    @Volatile
    private var sharedLoader: ImageLoader? = null

    fun imageLoader(context: Context): ImageLoader {
        val cached = sharedLoader
        if (cached != null) return cached

        return synchronized(this) {
            val again = sharedLoader
            if (again != null) {
                again
            } else {
                buildLoader(context.applicationContext).also { sharedLoader = it }
            }
        }
    }

    fun buildRequest(context: Context, appId: String, iconUrl: String): ImageRequest {
        val stableKey = stableCacheKey(appId, iconUrl)
        return ImageRequest.Builder(context)
            .data(iconUrl)
            .memoryCacheKey(stableKey)
            .diskCacheKey(stableKey)
            .crossfade(false)
            .build()
    }

    private fun buildLoader(context: Context): ImageLoader {
        val diskDir = File(context.cacheDir, DISK_CACHE_DIR)
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskDir)
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    private fun stableCacheKey(appId: String, iconUrl: String): String {
        return "tvapp:$appId:${iconUrl.hashCode()}"
    }
}

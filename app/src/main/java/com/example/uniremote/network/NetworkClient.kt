package com.example.uniremote.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared Network Client for all HTTP-based TV controllers (like Roku, Google TV pairing, etc.)
 * Prevents OkHttpClient connection pool leakage and excessive thread creation
 * by centralizing the network requests into a single instance.
 */
object NetworkClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

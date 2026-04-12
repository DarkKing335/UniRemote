package com.example.uniremote.cast

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter.
 */
class RequestRateLimiter(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(key: String): Boolean {
        val now = nowProvider()
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }

        synchronized(bucket) {
            while (bucket.isNotEmpty() && now - bucket.first() > windowMs) {
                bucket.removeFirst()
            }
            if (bucket.size >= maxEvents) {
                return false
            }
            bucket.addLast(now)
            return true
        }
    }

    fun clear() {
        buckets.clear()
    }
}

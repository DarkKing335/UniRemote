package com.example.uniremote.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRateLimiterTest {

    @Test
    fun `allow blocks when limit exceeded within window`() {
        var now = 0L
        val limiter = RequestRateLimiter(
            maxEvents = 2,
            windowMs = 1_000L,
            nowProvider = { now }
        )

        assertTrue(limiter.allow("k"))
        assertTrue(limiter.allow("k"))
        assertFalse(limiter.allow("k"))

        now = 1_100L
        assertTrue(limiter.allow("k"))
    }

    @Test
    fun `clear resets limiter state`() {
        val limiter = RequestRateLimiter(maxEvents = 1, windowMs = 10_000L)
        assertTrue(limiter.allow("ip"))
        assertFalse(limiter.allow("ip"))

        limiter.clear()

        assertTrue(limiter.allow("ip"))
    }
}

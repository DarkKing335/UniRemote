package com.example.uniremote.cast

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirroringSessionStressInstrumentedTest {

    @Test
    fun longRunningSession_slidingExpiryStaysAliveDuringActivity() {
        var now = 0L
        val controller = StreamSessionAccessController(nowProvider = { now })
        val credentials = controller.startSession(
            ttlMs = 1_000L,
            explicitToken = "token",
            serverIp = "192.168.1.5",
            restrictionMode = StreamSessionAccessController.ClientRestrictionMode.FIRST_CLIENT
        )

        repeat(120) {
            now += 400L
            val result = controller.validate(
                StreamSessionAccessController.AccessRequest(
                    credential = credentials.token,
                    clientIp = "192.168.1.9"
                )
            )
            assertEquals(StreamSessionAccessController.ValidationResult.Allowed, result)
            assertTrue(controller.hasActiveSession())
        }

        now += 1_500L
        assertFalse(controller.hasActiveSession())
    }

    @Test
    fun reconnectLoop_rateLimiterTriggersUnderBurst() {
        var now = 0L
        val limiter = RequestRateLimiter(
            maxEvents = 5,
            windowMs = 2_000L,
            nowProvider = { now }
        )

        repeat(5) {
            assertTrue(limiter.allow("client-1"))
            now += 200L
        }

        assertFalse(limiter.allow("client-1"))

        now += 2_100L
        assertTrue(limiter.allow("client-1"))
    }
}

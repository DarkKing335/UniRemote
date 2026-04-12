package com.example.uniremote.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionAccessControllerTest {

    @Test
    fun `token generation is high entropy and non-empty`() {
        val tokenA = StreamSessionAccessController.generateSessionToken()
        val tokenB = StreamSessionAccessController.generateSessionToken()

        assertTrue(tokenA.length >= 40)
        assertTrue(tokenB.length >= 40)
        assertNotEquals(tokenA, tokenB)
    }

    @Test
    fun `validate allows correct token before expiry`() {
        var now = 1_000L
        val controller = StreamSessionAccessController(nowProvider = { now })
        val creds = controller.startSession(
            ttlMs = 5_000,
            explicitToken = "abc",
            serverIp = "192.168.1.5",
            restrictionMode = StreamSessionAccessController.ClientRestrictionMode.NONE
        )

        val result = controller.validate(
            StreamSessionAccessController.AccessRequest(
                credential = creds.token,
                clientIp = "192.168.1.9"
            )
        )

        assertEquals(StreamSessionAccessController.ValidationResult.Allowed, result)
        assertTrue(controller.hasActiveSession())

        // Sliding TTL should keep the session alive after successful activity.
        now = 5_900L
        assertTrue(controller.hasActiveSession())

        // No activity afterwards -> expires.
        now = 11_100L
        val expired = controller.validate(
            StreamSessionAccessController.AccessRequest(
                credential = creds.token,
                clientIp = "192.168.1.9"
            )
        )
        assertEquals(StreamSessionAccessController.ValidationResult.Expired, expired)
        assertFalse(controller.hasActiveSession())
    }

    @Test
    fun `first client restriction pins ip`() {
        val controller = StreamSessionAccessController()
        val creds = controller.startSession(
            ttlMs = 60_000,
            explicitToken = "token",
            serverIp = "192.168.1.5",
            restrictionMode = StreamSessionAccessController.ClientRestrictionMode.FIRST_CLIENT
        )

        val first = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "192.168.1.9")
        )
        val same = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "192.168.1.9")
        )
        val other = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "192.168.1.10")
        )

        assertEquals(StreamSessionAccessController.ValidationResult.Allowed, first)
        assertEquals(StreamSessionAccessController.ValidationResult.Allowed, same)
        assertEquals(StreamSessionAccessController.ValidationResult.IpRestricted, other)
    }

    @Test
    fun `same subnet restriction only allows matching slash24`() {
        val controller = StreamSessionAccessController()
        val creds = controller.startSession(
            ttlMs = 60_000,
            explicitToken = "token",
            serverIp = "10.0.2.15",
            restrictionMode = StreamSessionAccessController.ClientRestrictionMode.SAME_SUBNET
        )

        val ok = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "10.0.2.20")
        )
        val bad = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "10.0.3.20")
        )

        assertEquals(StreamSessionAccessController.ValidationResult.Allowed, ok)
        assertEquals(StreamSessionAccessController.ValidationResult.IpRestricted, bad)
    }

    @Test
    fun `same subnet uses dynamic prefix length when provided`() {
        val controller = StreamSessionAccessController()
        val creds = controller.startSession(
            ttlMs = 60_000,
            explicitToken = "token",
            serverIp = "10.1.8.15",
            restrictionMode = StreamSessionAccessController.ClientRestrictionMode.SAME_SUBNET,
            serverPrefixLength = 16
        )

        val same16Different24 = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "10.1.99.20")
        )
        val outside16 = controller.validate(
            StreamSessionAccessController.AccessRequest(creds.token, "10.2.8.20")
        )

        assertEquals(StreamSessionAccessController.ValidationResult.Allowed, same16Different24)
        assertEquals(StreamSessionAccessController.ValidationResult.IpRestricted, outside16)
    }
}

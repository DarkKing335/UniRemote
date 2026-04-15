package com.example.uniremote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuControllerTest {

    @Test
    fun `maps unauthorized and forbidden statuses to network access guidance`() {
        val unauthorized = RokuController.mapEcpAccessFailure(401)
        val forbidden = RokuController.mapEcpAccessFailure(403)

        assertEquals(RokuController.ROKU_NETWORK_ACCESS_LIMITED_MESSAGE, unauthorized)
        assertEquals(RokuController.ROKU_NETWORK_ACCESS_LIMITED_MESSAGE, forbidden)
    }

    @Test
    fun `does not map unrelated statuses`() {
        assertNull(RokuController.mapEcpAccessFailure(null))
        assertNull(RokuController.mapEcpAccessFailure(200))
        assertNull(RokuController.mapEcpAccessFailure(404))
        assertNull(RokuController.mapEcpAccessFailure(500))
    }

    @Test
    fun `guidance message includes required Roku settings path`() {
        val message = RokuController.ROKU_NETWORK_ACCESS_LIMITED_MESSAGE.lowercase()

        assertTrue(message.contains("control by mobile apps"))
        assertTrue(message.contains("network access"))
        assertTrue(message.contains("enabled"))
    }
}

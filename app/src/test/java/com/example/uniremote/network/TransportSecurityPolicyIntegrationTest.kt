package com.example.uniremote.network

import com.example.uniremote.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityPolicyIntegrationTest {

    @Test
    fun `blocks non-lan cleartext urls`() {
        assertFalse(
            TransportSecurityPolicy.allowCleartextForUrl(
                "http://8.8.8.8:8060/keypress/Home",
                "Roku ECP"
            )
        )
    }

    @Test
    fun `allows lan cleartext urls`() {
        assertTrue(
            TransportSecurityPolicy.allowCleartextForUrl(
                "http://192.168.1.50:8060/keypress/Home",
                "Roku ECP"
            )
        )
    }

    @Test
    fun `always allows secure urls regardless of host`() {
        assertTrue(
            TransportSecurityPolicy.allowCleartextForUrl(
                "https://example.com/video.m3u8",
                "DLNA cast"
            )
        )
    }

    @Test
    fun `insecure protocol is denied for non-lan host even in compatibility mode`() {
        assertFalse(
            TransportSecurityPolicy.allowInsecureDeviceProtocol(
                protocol = "Roku ECP over http://",
                host = "8.8.8.8"
            )
        )
    }

    @Test
    fun `insecure protocol follows build flag for lan host`() {
        val allowed = TransportSecurityPolicy.allowInsecureDeviceProtocol(
            protocol = "Roku ECP over http://",
            host = "192.168.1.50"
        )

        if (BuildConfig.ENABLE_INSECURE_DEVICE_PROTOCOLS) {
            assertTrue(allowed)
        } else {
            assertFalse(allowed)
        }
    }
}

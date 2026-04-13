package com.example.uniremote.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConnectionManagerTest {

    @Test
    fun `sendKey throws when no active controller`() = runTest {
        val manager = DeviceConnectionManager()

        val thrown = runCatching {
            manager.sendKey(TvKey.OK)
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `launchApp throws when no active controller`() = runTest {
        val manager = DeviceConnectionManager()

        val thrown = runCatching {
            manager.launchApp("com.test.app")
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }
}

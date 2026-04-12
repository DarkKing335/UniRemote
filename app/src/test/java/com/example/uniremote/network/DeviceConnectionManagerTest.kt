package com.example.uniremote.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DeviceConnectionManagerTest {

    @Test
    fun `sendKey throws when no active controller`() = runTest {
        val manager = DeviceConnectionManager()

        assertFailsWith<IllegalStateException> {
            manager.sendKey(TvKey.OK)
        }
    }

    @Test
    fun `launchApp throws when no active controller`() = runTest {
        val manager = DeviceConnectionManager()

        assertFailsWith<IllegalStateException> {
            manager.launchApp("com.test.app")
        }
    }
}

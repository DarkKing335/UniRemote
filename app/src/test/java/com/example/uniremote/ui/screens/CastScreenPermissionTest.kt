package com.example.uniremote.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CastScreenPermissionTest {

    @Test
    fun `permission denial triggers denied branch only`() {
        var grantedCalls = 0
        var deniedCalls = 0

        handleNotificationPermissionResult(
            granted = false,
            onGranted = { grantedCalls++ },
            onDenied = { deniedCalls++ }
        )

        assertEquals(0, grantedCalls)
        assertEquals(1, deniedCalls)
    }

    @Test
    fun `permission granted triggers granted branch only`() {
        var grantedCalls = 0
        var deniedCalls = 0

        handleNotificationPermissionResult(
            granted = true,
            onGranted = { grantedCalls++ },
            onDenied = { deniedCalls++ }
        )

        assertEquals(1, grantedCalls)
        assertEquals(0, deniedCalls)
    }
}

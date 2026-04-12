package com.example.uniremote.cast

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastStateMachineTest {

    @Test
    fun `state machine transitions from send to playback to casting`() {
        val flow = MutableStateFlow<CastState>(CastState.Idle)
        val stateMachine = CastStateMachine(flow)

        stateMachine.onSendingUri("Demo", "Living Room TV")
        assertTrue(flow.value is CastState.SendingUri)

        stateMachine.onStartingPlayback("Demo", "Living Room TV")
        assertTrue(flow.value is CastState.StartingPlayback)

        stateMachine.onCasting("Demo", "Living Room TV")
        assertTrue(flow.value is CastState.Casting)
    }

    @Test
    fun `state machine enters error and returns idle on stop`() {
        val flow = MutableStateFlow<CastState>(CastState.Idle)
        val stateMachine = CastStateMachine(flow)

        stateMachine.onError("Play failed")
        assertTrue(flow.value is CastState.Error)
        assertEquals("Play failed", (flow.value as CastState.Error).message)

        stateMachine.onIdle()
        assertTrue(flow.value is CastState.Idle)
    }
}

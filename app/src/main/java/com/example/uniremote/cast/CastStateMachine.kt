package com.example.uniremote.cast

import kotlinx.coroutines.flow.MutableStateFlow

class CastStateMachine(
    private val stateFlow: MutableStateFlow<CastState>
) {
    fun onIdle() {
        stateFlow.value = CastState.Idle
    }

    fun onDiscovering() {
        stateFlow.value = CastState.Discovering
    }

    fun onSendingUri(title: String, rendererName: String) {
        stateFlow.value = CastState.SendingUri(title, rendererName)
    }

    fun onStartingPlayback(title: String, rendererName: String) {
        stateFlow.value = CastState.StartingPlayback(title, rendererName)
    }

    fun onCasting(title: String, rendererName: String) {
        stateFlow.value = CastState.Casting(title, rendererName)
    }

    fun onError(message: String) {
        stateFlow.value = CastState.Error(message)
    }
}

package com.example.uniremote.viewmodel

/**
 * Represents the current connection state of the TV remote.
 * Lives in the viewmodel package so all UI screens can import it from a stable location.
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    object Offline      : ConnectionStatus()   // TV is off / unreachable
    data class Error(val message: String) : ConnectionStatus()
}

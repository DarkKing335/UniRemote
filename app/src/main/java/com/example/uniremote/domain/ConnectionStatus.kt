package com.example.uniremote.domain

/**
 * Represents the current connection state of the TV remote.
 * Lives in the domain package so both the network and viewmodel layers
 * can import it without introducing an upward dependency.
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    object Offline      : ConnectionStatus()   // TV is off / unreachable
    data class Error(val message: String) : ConnectionStatus()
}

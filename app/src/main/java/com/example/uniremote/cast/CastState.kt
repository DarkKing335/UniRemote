package com.example.uniremote.cast

/** Represents the lifecycle state of a DLNA media cast session. */
sealed class CastState {
    /** No active operation. */
    object Idle : CastState()

    /** UPnP search in progress; waiting for renderers to appear. */
    object Discovering : CastState()

    /** AVTransport SetURI request is in progress. */
    data class SendingUri(val title: String, val rendererName: String) : CastState()

    /** URI accepted by renderer; waiting for Play confirmation. */
    data class StartingPlayback(val title: String, val rendererName: String) : CastState()

    /** A media item is actively being streamed to a TV renderer. */
    data class Casting(val title: String, val rendererName: String) : CastState()

    /** An error occurred during discovery or playback. */
    data class Error(val message: String) : CastState()
}

package com.example.uniremote.cast

interface CastManager {
    fun discoverDevices()
    fun castMedia(url: String)
    fun play()
    fun pause()
    fun seek(position: Long)
    fun setVolume(value: Int)

    fun startMirroring()
    fun stopMirroring()
}

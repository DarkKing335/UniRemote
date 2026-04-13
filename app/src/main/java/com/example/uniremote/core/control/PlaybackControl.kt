package com.example.uniremote.core.control

interface PlaybackControl {
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun setVolume(value: Int)
}

package com.example.uniremote.mirroring.encoder

data class H264EncodingProfile(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 24,
    val bitrate: Int = 2_500_000
)

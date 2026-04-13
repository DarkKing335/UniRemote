package com.example.uniremote.tv.player

enum class TvPlayerBackend {
    EXOPLAYER,
    VLC,
    UNKNOWN
}

data class TvPlayerProfile(
    val backend: TvPlayerBackend = TvPlayerBackend.UNKNOWN,
    val appPackage: String? = null
)

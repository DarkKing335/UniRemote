package com.example.uniremote.core.device

data class CastDevice(
    val id: String,
    val name: String,
    val model: String? = null,
    val protocol: String = "DLNA"
)

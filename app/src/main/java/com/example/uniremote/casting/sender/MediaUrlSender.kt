package com.example.uniremote.casting.sender

interface MediaUrlSender {
    fun sendMediaUrl(url: String, title: String = "Media")
}

package com.example.uniremote.network

/** A TV-installed app returned by the controller. */
data class TvApp(
    val id: String,
    val name: String,
    /** Optional URL/path to the app icon. Null = use fallback. */
    val iconUrl: String? = null
)

/** UI-ready representation used in Compose (resolved icon + tint). */
data class TvAppUiModel(
    val id: String,
    val name: String,
    val iconUrl: String? = null
)

fun TvApp.toUiModel() = TvAppUiModel(id = id, name = name, iconUrl = iconUrl)

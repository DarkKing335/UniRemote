package com.example.uniremote.data

import com.example.uniremote.network.TvKey

/**
 * A user-defined macro: a named sequence of TvKey presses.
 * Stored as JSON in DataStore (keys encoded as comma-separated enum names).
 */
data class UserMacro(
    val id: String,          // UUID string
    val name: String,        // display name, chosen by user
    val description: String, // optional short description
    val icon: String,        // icon name key (maps to icon in UI)
    val keys: List<TvKey>    // ordered list of keys to press
)

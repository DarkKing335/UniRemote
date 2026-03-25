package com.example.uniremote.network

/**
 * All standard TV remote keys mapped across brands.
 * Each controller translates these to brand-specific commands.
 */
enum class TvKey {
    // Navigation
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU, EXIT,

    // Volume
    VOL_UP, VOL_DOWN, MUTE,

    // Channel
    CH_UP, CH_DOWN,

    // Power
    POWER,

    // Color buttons
    RED, GREEN, YELLOW, BLUE,

    // Media playback
    PLAY, PAUSE, STOP, FF, RW, NEXT, PREV,

    // Numbers
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4,
    NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,

    // Input source
    SOURCE, HDMI_1, HDMI_2, HDMI_3, HDMI_4, AV, COMPONENT,

    // Special
    INFO, GUIDE, SETTINGS, SEARCH, NETFLIX, YOUTUBE,
    ASPECT_RATIO, PIC_MODE, SLEEP,

    // TV-specific
    SAMSUNG_SMART_HUB,
    LG_QUICK_MENU,
    ANDROID_LAUNCHER
}

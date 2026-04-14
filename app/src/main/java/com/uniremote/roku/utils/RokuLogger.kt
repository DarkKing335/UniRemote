package com.uniremote.roku.utils

import android.util.Log

object RokuLogger {
    private const val TAG = "RokuModule"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun d(message: String) {
        // You might want to wrap this in a BuildConfig.DEBUG check if desired
        Log.d(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}

package com.uniremote.roku.utils

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

object RokuErrorMapper {
    fun map(throwable: Throwable): String {
        return when (throwable) {
            is SocketTimeoutException -> "Device unreachable (port 8060 blocked)"
            is UnknownHostException -> "IP address changed, re-discovering..."
            is HttpException -> {
                if (throwable.code() == 403) {
                    "Network Access disabled on Roku"
                } else {
                    "HTTP Error: ${throwable.code()}"
                }
            }
            is IOException -> "Network error, check WiFi connection"
            else -> "Unexpected error occurred"
        }
    }
}

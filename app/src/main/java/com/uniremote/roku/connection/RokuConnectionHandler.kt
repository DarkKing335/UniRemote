package com.uniremote.roku.connection

import com.uniremote.roku.model.RokuDevice
import com.uniremote.roku.model.RokuDeviceState
import com.uniremote.roku.utils.RokuLogger
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import retrofit2.HttpException

class RokuConnectionHandler {

    interface OnStateChangeListener {
        fun onStateChanged(device: RokuDevice, newState: RokuDeviceState)
    }

    private var stateChangeListener: OnStateChangeListener? = null

    fun setOnStateChangeListener(listener: OnStateChangeListener) {
        this.stateChangeListener = listener
    }

    companion object {
        const val MAX_RETRIES = 3
        const val BLOCKED_DIALOG_MESSAGE = "Network verification failed. Please ensure 'Network Access' is set to 'Default' or 'Permissive' under Settings > System > Advanced System Settings on your Roku TV."
    }

    suspend fun <T> executeWithRetryAndStateTracking(device: RokuDevice, call: suspend () -> T): Result<T> {
        var currentDelay = 2000L // 2 seconds
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                val result = call()
                // If it succeeds, the device is fully controllable
                device.state = RokuDeviceState.CONTROL_AVAILABLE
                stateChangeListener?.onStateChanged(device, RokuDeviceState.CONTROL_AVAILABLE)
                return Result.success(result)
            } catch (e: Exception) {
                RokuLogger.w("Attempt $attempt failed: ${e.message}")
                
                // Check if the error is 403 or Timeout
                if (e is SocketTimeoutException || (e is HttpException && e.code() == 403)) {
                    device.state = RokuDeviceState.BLOCKED
                    stateChangeListener?.onStateChanged(device, RokuDeviceState.BLOCKED)
                    return Result.failure(Exception(BLOCKED_DIALOG_MESSAGE))
                }
                
                if (attempt == MAX_RETRIES) {
                    device.state = RokuDeviceState.UNREACHABLE
                    stateChangeListener?.onStateChanged(device, RokuDeviceState.UNREACHABLE)
                    return Result.failure(e)
                }
                
                delay(currentDelay)
                currentDelay *= 2 // exponential backoff (2s, 4s, 8s)
            }
        }
        
        return Result.failure(Exception("Max retries exceeded"))
    }
}

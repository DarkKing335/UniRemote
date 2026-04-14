package com.uniremote.roku.control

import android.content.Context
import android.hardware.ConsumerIrManager
import com.uniremote.roku.utils.RokuLogger

/**
 * Legitimate IR Fallback Controller for Roku TVs.
 * This module is used as a fallback when Network Access is disabled on the TV.
 * Note: IR codes vary by TV manufacturer (TCL, Hisense, etc.). 
 * These are standard NEC-protocol codes commonly used by Roku TVs.
 */
class RokuIRController(context: Context) {

    private val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    // Standard carrier frequency for Roku TV IR (38kHz)
    private val CARRIER_FREQUENCY = 38000

    // Common Roku TV IR Codes (NEC Protocol)
    // Format: Frequency, Pattern... (Simplified for this module)
    private val rokuIrPatterns = mapOf(
        "Home" to intArrayOf(9000, 4500, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 40000),
        "Up" to intArrayOf(9000, 4500, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 40000),
        "Down" to intArrayOf(9000, 4500, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 40000),
        "Power" to intArrayOf(9000, 4500, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 40000)
    )

    fun isIrSupported(): Boolean {
        return irManager?.hasIrEmitter() == true
    }

    fun transmit(key: String): Result<Unit> {
        val manager = irManager ?: return Result.failure(Exception("IR hardware not found"))
        val pattern = rokuIrPatterns[key] ?: return Result.failure(Exception("IR code not mapped for key: $key"))

        return try {
            if (manager.hasIrEmitter()) {
                RokuLogger.i("Transmitting IR code for key: $key")
                manager.transmit(CARRIER_FREQUENCY, pattern)
                Result.success(Unit)
            } else {
                Result.failure(Exception("IR emitter not available"))
            }
        } catch (e: Exception) {
            RokuLogger.e("IR transmission failed", e)
            Result.failure(e)
        }
    }
}

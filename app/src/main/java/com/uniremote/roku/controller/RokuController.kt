package com.uniremote.roku.controller

import com.uniremote.roku.model.RokuApp
import com.uniremote.roku.model.RokuDevice
import com.uniremote.roku.model.RokuDeviceState
import com.uniremote.roku.model.RokuKey

interface RokuController {
    suspend fun sendKeypress(device: RokuDevice, key: RokuKey): Result<Unit>
    suspend fun launchApp(device: RokuDevice, appId: String): Result<Unit>
    suspend fun getDeviceInfo(device: RokuDevice): Result<String>
    suspend fun getApps(device: RokuDevice): Result<List<RokuApp>>
    suspend fun sendText(device: RokuDevice, text: String): Result<Unit>
    fun getDeviceState(device: RokuDevice): RokuDeviceState
}

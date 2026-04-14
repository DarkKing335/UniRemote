package com.uniremote.roku.integration

import android.content.Context
import com.uniremote.roku.controller.RokuController
import com.uniremote.roku.controller.RokuControllerImpl

object RokuProvider {
    private var controllerInstance: RokuController? = null

    fun initialize(context: Context) {
        if (controllerInstance == null) {
            controllerInstance = RokuControllerImpl()
        }
    }

    fun getController(): RokuController {
        return controllerInstance ?: throw IllegalStateException("RokuProvider not initialized. Call initialize(context) in Application class.")
    }
}

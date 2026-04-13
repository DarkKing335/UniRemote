package com.example.uniremote.mirroring.capture

import android.content.Intent

data class MirroringGrant(
    val resultCode: Int,
    val data: Intent
)

class MirroringGrantStore {
    @Volatile
    private var currentGrant: MirroringGrant? = null

    fun setGrant(resultCode: Int, data: Intent) {
        currentGrant = MirroringGrant(resultCode, Intent(data))
    }

    fun getGrant(): MirroringGrant? = currentGrant

    fun clear() {
        currentGrant = null
    }
}

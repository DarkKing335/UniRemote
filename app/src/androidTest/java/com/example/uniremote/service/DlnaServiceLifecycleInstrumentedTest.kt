package com.example.uniremote.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uniremote.dlna.dlna.DlnaUpnpService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlnaServiceLifecycleInstrumentedTest {

    @Test
    fun dlnaService_canStartAndStopWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val startIntent = Intent(context, DlnaUpnpService::class.java)

        val component = context.startService(startIntent)
        assertTrue(component != null)

        val stopped = context.stopService(startIntent)
        assertTrue(stopped)
    }
}

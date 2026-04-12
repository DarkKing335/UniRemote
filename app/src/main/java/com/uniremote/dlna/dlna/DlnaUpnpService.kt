package com.uniremote.dlna.dlna

import android.util.Log
import org.jupnp.android.AndroidUpnpServiceImpl

/**
 * Thin wrapper over [AndroidUpnpServiceImpl] that guards against a known jUPnP bug:
 *
 * `AndroidUpnpServiceImpl.onDestroy()` calls `router.unregisterBroadcastReceiver()`
 * without null-checking `router`. If the service is stopped before the UPnP stack
 * finishes initialising (e.g. user leaves the Cast screen quickly), `router` is
 * still null → NullPointerException → process crash.
 *
 * Fix: catch the NPE so the app does not crash. When the router is null the stack
 * never started, so there is nothing to tear down and it is safe to swallow the error.
 */
class DlnaUpnpService : AndroidUpnpServiceImpl() {

    override fun onDestroy() {
        try {
            super.onDestroy()
        } catch (e: NullPointerException) {
            // Swallow the jUPnP bug: AndroidRouter.unregisterBroadcastReceiver()
            // called on a null router when the service is destroyed before init completes.
            Log.w("DlnaUpnpService", "onDestroy: suppressed jUPnP NPE (router not initialised): ${e.message}")
        }
    }
}


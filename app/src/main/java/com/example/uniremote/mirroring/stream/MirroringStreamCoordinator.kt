package com.example.uniremote.mirroring.stream

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.uniremote.cast.ScreenMirrorService
import com.example.uniremote.mirroring.capture.MirroringGrantStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates MediaProjection-based screen mirroring service lifecycle.
 */
class MirroringStreamCoordinator(
    private val app: Application,
    private val grantStore: MirroringGrantStore = MirroringGrantStore()
) {

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _streamUrl = MutableStateFlow<String?>(null)
    val streamUrl: StateFlow<String?> = _streamUrl.asStateFlow()

    private val _authHint = MutableStateFlow<String?>(null)
    val authHint: StateFlow<String?> = _authHint.asStateFlow()

    private val _tlsFingerprint = MutableStateFlow<String?>(null)
    val tlsFingerprint: StateFlow<String?> = _tlsFingerprint.asStateFlow()

    private var authHeader: String? = null

    fun setProjectionGrant(resultCode: Int, data: Intent) {
        grantStore.setGrant(resultCode, data)
    }

    fun start(onSessionReady: ((String) -> Unit)? = null) {
        val grant = grantStore.getGrant()
            ?: throw IllegalStateException("Mirroring projection grant is not configured")

        val sessionToken = ScreenMirrorService.generateSessionToken()

        ScreenMirrorService.onSessionStarted = { publicEndpoint, authorizationHeader, maskedToken, tlsFp ->
            val token = authorizationHeader
                .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
                .orEmpty()

            val authorizedEndpoint = if (token.isNotBlank()) {
                val base = Uri.parse(publicEndpoint)
                base.buildUpon()
                    .appendQueryParameter("token", token)
                    .build()
                    .toString()
            } else {
                publicEndpoint
            }

            _streamUrl.value = authorizedEndpoint
            _authHint.value = maskedToken
            _tlsFingerprint.value = tlsFp
            authHeader = authorizationHeader
            _isMirroring.value = true

            onSessionReady?.invoke(authorizedEndpoint)
        }

        ScreenMirrorService.onStopped = {
            clearState()
        }

        val intent = Intent(app, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_START
            putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, grant.data)
            putExtra(ScreenMirrorService.EXTRA_SESSION_TOKEN, sessionToken)
            putExtra(
                ScreenMirrorService.EXTRA_CLIENT_RESTRICTION_MODE,
                ScreenMirrorService.RESTRICTION_SAME_SUBNET
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }

        _streamUrl.value = null
        _authHint.value = ScreenMirrorService.maskToken(sessionToken)
        authHeader = null
    }

    fun stop() {
        app.startService(
            Intent(app, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_STOP
            }
        )
        clearState()
        grantStore.clear()
    }

    fun getAuthorizationHeaderForManualShare(): String? = authHeader

    fun release() {
        ScreenMirrorService.onStopped = null
        ScreenMirrorService.onSessionStarted = null
    }

    private fun clearState() {
        _isMirroring.value = false
        _streamUrl.value = null
        _authHint.value = null
        _tlsFingerprint.value = null
        authHeader = null
    }
}

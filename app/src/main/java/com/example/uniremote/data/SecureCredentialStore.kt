package com.example.uniremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "secure_credentials"
private const val KEY_TLS_PRIV = "tls_priv_pkcs8"
private const val KEY_TLS_CERT = "tls_cert_x509"

class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getDeviceToken(deviceId: String): String? = prefs.getString(tokenKey(deviceId), null)

    fun putDeviceToken(deviceId: String, token: String) {
        prefs.edit().putString(tokenKey(deviceId), token).apply()
    }

    fun removeDeviceToken(deviceId: String) {
        prefs.edit().remove(tokenKey(deviceId)).apply()
    }

    fun getTlsPrivateKey(): String? = prefs.getString(KEY_TLS_PRIV, null)

    fun getTlsCertificate(): String? = prefs.getString(KEY_TLS_CERT, null)

    fun putTlsMaterial(privateKeyB64: String, certB64: String) {
        prefs.edit()
            .putString(KEY_TLS_PRIV, privateKeyB64)
            .putString(KEY_TLS_CERT, certB64)
            .apply()
    }

    private fun tokenKey(deviceId: String): String = "token_$deviceId"
}

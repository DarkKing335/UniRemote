package com.uniremote.roku.controller

import com.uniremote.roku.connection.RokuConnectionHandler
import com.uniremote.roku.ecp.RokuEcpClient
import com.uniremote.roku.model.RokuApp
import com.uniremote.roku.model.RokuDevice
import com.uniremote.roku.model.RokuDeviceState
import com.uniremote.roku.model.RokuKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.simplexml.SimpleXmlConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class RokuControllerImpl : RokuController {

    private val connectionHandler = RokuConnectionHandler()
    private val clientCache = ConcurrentHashMap<String, RokuEcpClient>()

    private val baseOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun getClient(device: RokuDevice): RokuEcpClient {
        return clientCache.getOrPut(device.deviceId) {
            val retrofit = Retrofit.Builder()
                .baseUrl(device.locationUrl) // Expects "http://ip:8060"
                .client(baseOkHttpClient)
                .addConverterFactory(SimpleXmlConverterFactory.create())
                .build()
            retrofit.create(RokuEcpClient::class.java)
        }
    }

    override suspend fun sendKeypress(device: RokuDevice, key: RokuKey): Result<Unit> = withContext(Dispatchers.IO) {
        val client = getClient(device)
        connectionHandler.executeWithRetryAndStateTracking(device) {
            val response = client.keypress(key.value)
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
        }
    }

    override suspend fun launchApp(device: RokuDevice, appId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = getClient(device)
        connectionHandler.executeWithRetryAndStateTracking(device) {
            val response = client.launchApp(appId)
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
        }
    }

    override suspend fun getDeviceInfo(device: RokuDevice): Result<String> = withContext(Dispatchers.IO) {
        val client = getClient(device)
        connectionHandler.executeWithRetryAndStateTracking(device) {
            val response = client.getDeviceInfo()
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
            response.body()?.string() ?: ""
        }
    }

    override suspend fun getApps(device: RokuDevice): Result<List<RokuApp>> = withContext(Dispatchers.IO) {
        val client = getClient(device)
        connectionHandler.executeWithRetryAndStateTracking(device) {
            val response = client.getApps()
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
            
            // Basic extraction (Assuming SimpleXML parsing via raw string for demo ease, 
            // properly you'd parse the XML node list)
            val xml = response.body()?.string() ?: ""
            // Simple mock extraction based on standard Roku format: <app id="12" version="...">Netflix</app>
            val apps = mutableListOf<RokuApp>()
            val pattern = "<app id=\"(.*?)\" version=\"(.*?)\">(.*?)</app>".toRegex()
            pattern.findAll(xml).forEach { matchResult ->
                val (id, version, name) = matchResult.destructured
                apps.add(RokuApp(id, name, version))
            }
            apps
        }
    }

    override suspend fun sendText(device: RokuDevice, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = getClient(device)
        connectionHandler.executeWithRetryAndStateTracking(device) {
            val response = client.keypressLit(text)
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
        }
    }

    override fun getDeviceState(device: RokuDevice): RokuDeviceState {
        return device.state
    }
}

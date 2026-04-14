package com.uniremote.roku.ecp

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface RokuEcpClient {
    @POST("/keypress/{key}")
    suspend fun keypress(@Path("key") key: String): Response<Void>

    @POST("/keypress/Lit_{text}")
    suspend fun keypressLit(@Path("text") text: String): Response<Void>

    @POST("/launch/{appId}")
    suspend fun launchApp(@Path("appId") appId: String): Response<Void>

    @GET("/query/device-info")
    suspend fun getDeviceInfo(): Response<ResponseBody>

    @GET("/query/apps")
    suspend fun getApps(): Response<ResponseBody>

    @GET("/query/active-app")
    suspend fun getActiveApp(): Response<ResponseBody>
}

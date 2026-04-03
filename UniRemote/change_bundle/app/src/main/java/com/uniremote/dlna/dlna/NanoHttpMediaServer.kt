package com.uniremote.dlna.dlna

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.FileNotFoundException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class NanoHttpMediaServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val routes = ConcurrentHashMap<String, Route>()

    data class Route(
        val uri: Uri,
        val mimeType: String,
        val displayName: String
    )

    fun addMedia(uri: Uri, mimeType: String, displayName: String): String {
        val token = URLEncoder.encode(displayName, "UTF-8") + "-" + System.currentTimeMillis()
        routes[token] = Route(uri, mimeType, displayName)
        return token
    }

    fun removeRoute(token: String) {
        routes.remove(token)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET is allowed")
        }

        val token = session.uri.removePrefix("/")
        val route = routes[token]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media route not found")

        return try {
            val input = context.contentResolver.openInputStream(route.uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot open media")
            val length = context.contentResolver.openAssetFileDescriptor(route.uri, "r")?.length ?: -1L
            newChunkedResponse(Response.Status.OK, route.mimeType, input).apply {
                addHeader("Content-Disposition", "inline; filename=\"${route.displayName}\"")
                if (length > 0) {
                    addHeader("Content-Length", length.toString())
                }
                addHeader("Accept-Ranges", "bytes")
            }
        } catch (_: FileNotFoundException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media not found")
        }
    }
}

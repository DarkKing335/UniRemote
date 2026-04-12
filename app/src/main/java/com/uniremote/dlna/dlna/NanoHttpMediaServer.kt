package com.uniremote.dlna.dlna

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.FileNotFoundException

class NanoHttpMediaServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        const val ACTIVE_MEDIA_PATH: String = "media"
    }

    @Volatile
    private var activeRoute: Route? = null

    data class Route(
        val uri: Uri,
        val mimeType: String,
        val displayName: String
    )

    fun setActiveMedia(uri: Uri, mimeType: String, displayName: String): String {
        activeRoute = Route(uri, mimeType, displayName)
        return ACTIVE_MEDIA_PATH
    }

    fun clearActiveMedia() {
        activeRoute = null
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET is allowed")
        }

        val path = session.uri.removePrefix("/")
        if (path != ACTIVE_MEDIA_PATH) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media route not found")
        }

        val route = activeRoute
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

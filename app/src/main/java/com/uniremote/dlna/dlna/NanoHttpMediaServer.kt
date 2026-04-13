package com.uniremote.dlna.dlna

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.min

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
        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Only GET and HEAD are allowed"
            )
        }

        val path = session.uri.removePrefix("/")
        if (path != ACTIVE_MEDIA_PATH) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media route not found")
        }

        val route = activeRoute
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media route not found")

        return try {
            val totalLength = resolveLength(route.uri)
            val rangeHeader = session.headers["range"] ?: session.headers["Range"]
            val hasRangeRequest = !rangeHeader.isNullOrBlank()
            val range = parseSingleByteRange(rangeHeader, totalLength)

            if (hasRangeRequest && range == null) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    MIME_PLAINTEXT,
                    "Invalid or unsupported range"
                ).apply {
                    if (totalLength > 0L) {
                        addHeader("Content-Range", "bytes */$totalLength")
                    }
                    addHeader("Accept-Ranges", "bytes")
                }
            }

            val isPartial = range != null
            val start = range?.first ?: 0L
            val end = range?.last ?: if (totalLength > 0L) totalLength - 1L else -1L
            val payloadLength = if (isPartial && end >= start) {
                end - start + 1L
            } else {
                totalLength
            }
            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK

            if (method == Method.HEAD) {
                return newFixedLengthResponse(status, route.mimeType, "").apply {
                    addHeader("Content-Disposition", "inline; filename=\"${route.displayName}\"")
                    addHeader("Accept-Ranges", "bytes")
                    if (payloadLength > 0L) {
                        addHeader("Content-Length", payloadLength.toString())
                    }
                    if (isPartial && totalLength > 0L) {
                        addHeader("Content-Range", "bytes $start-$end/$totalLength")
                    }
                }
            }

            val input = context.contentResolver.openInputStream(route.uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot open media")

            if (start > 0L) {
                skipFully(input, start)
            }

            val body: InputStream = if (isPartial && payloadLength > 0L) {
                BoundedInputStream(input, payloadLength)
            } else {
                input
            }

            val response = if (payloadLength > 0L) {
                newFixedLengthResponse(status, route.mimeType, body, payloadLength)
            } else {
                newChunkedResponse(status, route.mimeType, body)
            }

            response.apply {
                addHeader("Content-Disposition", "inline; filename=\"${route.displayName}\"")
                addHeader("Accept-Ranges", "bytes")
                if (isPartial && totalLength > 0L) {
                    addHeader("Content-Range", "bytes $start-$end/$totalLength")
                }
            }
        } catch (_: FileNotFoundException) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media not found")
        }
    }

    private fun resolveLength(uri: Uri): Long {
        val assetLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (assetLength > 0L) return assetLength

        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    -1L
                }
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun parseSingleByteRange(rangeHeader: String?, totalLength: Long): LongRange? {
        if (rangeHeader.isNullOrBlank()) return null
        if (totalLength <= 0L) return null

        val raw = rangeHeader.trim()
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null

        val firstSpec = raw.substringAfter('=').substringBefore(',').trim()
        val dash = firstSpec.indexOf('-')
        if (dash <= -1) return null

        val startPart = firstSpec.substring(0, dash).trim()
        val endPart = firstSpec.substring(dash + 1).trim()
        val streamEnd = totalLength - 1L

        return when {
            startPart.isEmpty() -> {
                val suffixLength = endPart.toLongOrNull() ?: return null
                if (suffixLength <= 0L) return null
                val start = (totalLength - suffixLength).coerceAtLeast(0L)
                start..streamEnd
            }

            endPart.isEmpty() -> {
                val start = startPart.toLongOrNull() ?: return null
                if (start < 0L || start > streamEnd) return null
                start..streamEnd
            }

            else -> {
                val start = startPart.toLongOrNull() ?: return null
                var end = endPart.toLongOrNull() ?: return null
                if (start < 0L || start > streamEnd) return null
                if (end < start) return null
                end = min(end, streamEnd)
                start..end
            }
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) break
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    private class BoundedInputStream(
        private val upstream: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val byte = upstream.read()
            if (byte != -1) remaining -= 1L
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val maxRead = min(len.toLong(), remaining).toInt()
            val read = upstream.read(b, off, maxRead)
            if (read > 0) remaining -= read.toLong()
            return read
        }

        override fun close() {
            upstream.close()
        }
    }
}

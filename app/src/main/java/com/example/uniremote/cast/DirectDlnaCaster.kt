package com.example.uniremote.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTP/SOAP DLNA control client — does NOT require jUPnP to be running.
 *
 * Works by:
 * 1. HTTP GET device description URL → parse AVTransport controlURL
 * 2. HTTP POST SOAP SetAVTransportURI
 * 3. HTTP POST SOAP Play
 *
 * This is the same approach VLC and most working DLNA apps use on Android.
 */
internal class DirectDlnaCaster {

    companion object {
        private const val TAG = "DirectDlna"
        private const val AV_TRANSPORT_SERVICE_TYPE =
            "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SOAP_ENV =
            "http://schemas.xmlsoap.org/soap/envelope/"
        private const val SOAP_ENC =
            "http://schemas.xmlsoap.org/soap/encoding/"
        private const val TIMEOUT_MS = 5000
    }

    /** Resolved per-device control URL (cached after first fetch). */
    private val controlUrlCache = mutableMapOf<String, String>() // udn → full controlURL

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetch device description from [location], resolve AVTransport control URL,
     * send SetAVTransportURI + Play. Runs on IO dispatcher.
     */
    suspend fun cast(
        udn: String,
        location: String,
        mediaUrl: String,
        title: String,
        mimeType: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val controlUrl = controlUrlCache.getOrPut(udn) {
                resolveAvTransportControlUrl(location)
                    ?: run { onFailure("Cannot find AVTransport control URL from $location"); return@withContext }
            }

            Log.i(TAG, "Casting to controlUrl=$controlUrl mediaUrl=$mediaUrl")

            val setUriResult = soapPost(
                url        = controlUrl,
                action     = "SetAVTransportURI",
                serviceType = AV_TRANSPORT_SERVICE_TYPE,
                body       = setUriBody(mediaUrl, title, mimeType)
            )
            if (setUriResult.isFailure) {
                // Retry once with empty metadata
                val retry = soapPost(
                    url         = controlUrl,
                    action      = "SetAVTransportURI",
                    serviceType = AV_TRANSPORT_SERVICE_TYPE,
                    body        = setUriBody(mediaUrl, title, mimeType, emptyMeta = true)
                )
                if (retry.isFailure) {
                    onFailure("SetAVTransportURI failed: ${retry.exceptionOrNull()?.message}")
                    return@withContext
                }
            }

            delay(200L)

            val playResult = soapPost(
                url         = controlUrl,
                action      = "Play",
                serviceType  = AV_TRANSPORT_SERVICE_TYPE,
                body        = playBody()
            )
            if (playResult.isFailure) {
                onFailure("Play failed: ${playResult.exceptionOrNull()?.message}")
                return@withContext
            }

            Log.i(TAG, "Cast successful")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Direct cast error", e)
            onFailure("Direct DLNA error: ${e.message}")
        }
    }

    suspend fun stop(udn: String, location: String) = withContext(Dispatchers.IO) {
        runCatching {
            val controlUrl = controlUrlCache.getOrPut(udn) {
                resolveAvTransportControlUrl(location) ?: return@withContext
            }
            soapPost(controlUrl, "Stop", AV_TRANSPORT_SERVICE_TYPE, stopBody())
            Log.i(TAG, "Stop sent to $udn")
        }.onFailure { Log.w(TAG, "stop failed", it) }
    }

    suspend fun play(udn: String, location: String) = withContext(Dispatchers.IO) {
        runCatching {
            val controlUrl = controlUrlCache.getOrPut(udn) {
                resolveAvTransportControlUrl(location) ?: return@withContext
            }
            soapPost(controlUrl, "Play", AV_TRANSPORT_SERVICE_TYPE, playBody())
            Log.i(TAG, "Play sent to $udn")
        }.onFailure { Log.w(TAG, "play failed", it) }
    }

    suspend fun pause(udn: String, location: String) = withContext(Dispatchers.IO) {
        runCatching {
            val controlUrl = controlUrlCache.getOrPut(udn) {
                resolveAvTransportControlUrl(location) ?: return@withContext
            }
            soapPost(controlUrl, "Pause", AV_TRANSPORT_SERVICE_TYPE, pauseBody())
            Log.i(TAG, "Pause sent to $udn")
        }.onFailure { Log.w(TAG, "pause failed", it) }
    }

    fun clearCache(udn: String) { controlUrlCache.remove(udn) }
    fun clearAllCache() { controlUrlCache.clear() }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * HTTP GET [location] (device description XML) and extract AVTransport controlURL.
     * Returns the full absolute control URL, or null on failure.
     */
    private fun resolveAvTransportControlUrl(location: String): String? {
        val xml = try {
            URL(location).openConnection().apply {
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
            }.getInputStream().bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device description from $location", e)
            return null
        }

        val baseUrl = try {
            val u = URL(location)
            "${u.protocol}://${u.host}:${u.port}"
        } catch (_: Exception) { return null }

        return parseAvTransportControlUrl(xml, baseUrl)
    }

    private fun parseAvTransportControlUrl(xml: String, baseUrl: String): String? {
        var inAvTransport = false
        var controlUrl: String? = null
        var currentTag = ""

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                XmlPullParser.END_TAG -> {
                    if ((parser.name ?: "").equals("service", ignoreCase = true)) {
                        inAvTransport = false
                    }
                    currentTag = ""
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    when {
                        currentTag.equals("serviceType", ignoreCase = true) &&
                                text.contains("AVTransport", ignoreCase = true) ->
                            inAvTransport = true
                        inAvTransport && currentTag.equals("controlURL", ignoreCase = true) ->
                            controlUrl = if (text.startsWith("http")) text
                                         else "$baseUrl/${text.trimStart('/')}"
                    }
                }
            }
            event = parser.next()
            if (controlUrl != null && inAvTransport) break
        }

        Log.d(TAG, "Resolved AVTransport controlURL=$controlUrl from baseUrl=$baseUrl")
        return controlUrl
    }

    /** HTTP POST a SOAP action and return success/failure. */
    private fun soapPost(
        url: String,
        action: String,
        serviceType: String,
        body: String
    ): Result<String> {
        return runCatching {
            val soapAction = "\"$serviceType#$action\""
            val envelope = buildEnvelope(serviceType, action, body)
            val bytes = envelope.toByteArray(Charsets.UTF_8)

            Log.d(TAG, "SOAP POST $action → $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.requestMethod  = "POST"
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", soapAction)
            conn.setRequestProperty("Content-Length", bytes.size.toString())

            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            if (code in 200..299) {
                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "SOAP $action OK code=$code")
                response
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                throw Exception("HTTP $code: $err")
            }
        }.onFailure { Log.w(TAG, "SOAP $action failed: ${it.message}") }
    }

    // ── SOAP bodies ────────────────────────────────────────────────────────────

    private fun buildEnvelope(serviceType: String, action: String, body: String) =
        """<?xml version="1.0" encoding="utf-8"?>""" +
        """<s:Envelope xmlns:s="$SOAP_ENV" s:encodingStyle="$SOAP_ENC">""" +
        """<s:Body><u:$action xmlns:u="$serviceType">$body</u:$action></s:Body>""" +
        """</s:Envelope>"""

    private fun setUriBody(mediaUrl: String, title: String, mimeType: String, emptyMeta: Boolean = false): String {
        val meta = if (emptyMeta) "" else didlMeta(title, mediaUrl, mimeType)
        return "<InstanceID>0</InstanceID>" +
               "<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>" +
               "<CurrentURIMetaData>${escapeXml(meta)}</CurrentURIMetaData>"
    }

    private fun playBody()  = "<InstanceID>0</InstanceID><Speed>1</Speed>"
    private fun pauseBody() = "<InstanceID>0</InstanceID>"
    private fun stopBody()  = "<InstanceID>0</InstanceID>"

    private fun didlMeta(title: String, url: String, mimeType: String): String {
        val upnpClass = when {
            mimeType.startsWith("video") -> "object.item.videoItem"
            mimeType.startsWith("image") -> "object.item.imageItem"
            mimeType.startsWith("audio") -> "object.item.audioItem"
            else                         -> "object.item"
        }
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
               """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
               """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
               """<item id="0" parentID="0" restricted="1">""" +
               """<dc:title>${escapeXml(title)}</dc:title>""" +
               """<upnp:class>$upnpClass</upnp:class>""" +
               """<res protocolInfo="http-get:*:$mimeType:*">${escapeXml(url)}</res>""" +
               """</item></DIDL-Lite>"""
    }

    private fun escapeXml(v: String) = v
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

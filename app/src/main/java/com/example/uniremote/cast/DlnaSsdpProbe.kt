package com.example.uniremote.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentSkipListSet

/**
 * SSDP-based DLNA renderer discovery.
 *
 * Sends one M-SEARCH for ssdp:all, collects all LOCATION URLs within [timeoutMs],
 * then HTTP-fetches each device description in parallel to find AVTransport renderers.
 *
 * This is exactly how VLC discovers DLNA renderers on Android.
 */
internal class DlnaSsdpProbe {

    data class DiscoveredRenderer(
        val udn: String,
        val name: String,
        val model: String?,
        val location: String,
        val host: String
    )

    companion object {
        private const val TAG = "DlnaSsdpProbe"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }

    /**
     * Discover all DLNA renderers on the LAN.
     * Uses ssdp:all to get EVERY UPnP device, then filters to those with AVTransport.
     * If [hintIp] is provided, also sends a direct unicast M-SEARCH to that IP,
     * bypassing multicast routing issues.
     */
    suspend fun discover(timeoutMs: Int = 3000, hintIp: String? = null): List<DiscoveredRenderer> = withContext(Dispatchers.IO) {
        val locations = ConcurrentSkipListSet<String>()

        // Single search — ssdp:all returns everything (MediaRenderer, server, etc.)
        // We then filter by AVTransport presence.
        val targets = listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaRenderer:1")

        for (st in targets) {
            runCatching {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", 0))
                    soTimeout = 500   // per-read timeout; loop runs until full [timeoutMs] elapses
                }
                socket.use { sock ->
                    val bytes = buildMSearch(st).toByteArray(Charsets.UTF_8)
                    val addr  = InetAddress.getByName(SSDP_ADDR)
                    sock.send(DatagramPacket(bytes, bytes.size, addr, SSDP_PORT))
                    Thread.sleep(100)
                    sock.send(DatagramPacket(bytes, bytes.size, addr, SSDP_PORT))
                    Log.d(TAG, "M-SEARCH → $st")

                    val buf      = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)   // blocks up to soTimeout (500ms)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue            // no packet this window — keep waiting
                        } catch (_: Exception) {
                            break               // real socket error — give up
                        }
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val loc  = extractHeader(text, "LOCATION")
                        if (!loc.isNullOrBlank() && locations.add(loc)) {
                            Log.d(TAG, "LOCATION from ${pkt.address.hostAddress}: $loc")
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "M-SEARCH $st failed: ${it.message}") }
        }

        Log.i(TAG, "SSDP scan complete — ${locations.size} unique locations: $locations")

        // Unicast fallback: send direct M-SEARCH to known device IP (bypasses multicast)
        if (hintIp != null) {
            unicastSearch(hintIp, timeoutMs / 2, locations)
        }

        // Fetch device descriptions sequentially and filter to renderers with AVTransport
        locations.mapNotNull { location ->
            runCatching { fetchRenderer(location) }
                .onFailure { Log.w(TAG, "Device desc fetch failed $location: ${it.message}") }
                .getOrNull()
        }
    }

    /** Old API kept for backwards compatibility */
    fun runProbe(attempt: Int, timeoutMs: Int = 1800, log: (String) -> Unit) {
        log("DlnaSsdpProbe.runProbe() deprecated — use discover() instead")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends M-SEARCH UNICAST directly to [ip]:1900.
     * Unlike multicast (239.255.255.250), unicast goes directly to the device,
     * bypassing any multicast routing issues in the Android network stack.
     * Response packets are collected into [locations].
     */
    private fun unicastSearch(ip: String, timeoutMs: Int, locations: MutableCollection<String>) {
        runCatching {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", 0))
                soTimeout = 500
            }
            sock.use {
                val bytes = buildMSearch("ssdp:all").toByteArray(Charsets.UTF_8)
                it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), SSDP_PORT))
                Log.d(TAG, "Unicast M-SEARCH → $ip:$SSDP_PORT")

                val buf      = ByteArray(8192)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { it.receive(pkt) }
                    catch (_: java.net.SocketTimeoutException) { continue }
                    catch (_: Exception) { break }
                    val loc = extractHeader(String(pkt.data, 0, pkt.length, Charsets.UTF_8), "LOCATION")
                    if (!loc.isNullOrBlank() && locations.add(loc)) {
                        Log.d(TAG, "Unicast LOCATION from $ip: $loc")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Unicast search to $ip failed: ${it.message}") }
    }

    private fun buildMSearch(st: String) =
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $st\r\n" +
        "USER-AGENT: UniRemote/1.0 UPnP/1.1 Android\r\n" +
        "\r\n"

    private fun extractHeader(response: String, header: String): String? =
        response.lines()
            .firstOrNull { it.startsWith("$header:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun fetchRenderer(location: String): DiscoveredRenderer? {
        val url  = URL(location)
        val host = url.host
        val xml  = url.openConnection().apply {
            connectTimeout = 3000
            readTimeout    = 3000
        }.getInputStream().bufferedReader().readText()
        Log.d(TAG, "Device XML from $host (${xml.length} chars)")
        return parseDeviceXml(xml, location, host)
    }

    private fun parseDeviceXml(xml: String, location: String, host: String): DiscoveredRenderer? {
        var udn   = ""
        var name  = ""
        var model: String? = null
        var hasAvTransport = false
        var currentTag = ""
        var inDevice    = false
        var deviceDepth = -1
        var inService   = false
        var depth       = 0

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    currentTag = parser.name ?: ""
                    when {
                        currentTag.equals("device", ignoreCase = true) && deviceDepth == -1 -> {
                            inDevice = true; deviceDepth = depth
                        }
                        currentTag.equals("service", ignoreCase = true) -> inService = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    val t = parser.name ?: ""
                    if (t.equals("service", ignoreCase = true)) inService = false
                    if (t.equals("device",  ignoreCase = true) && depth == deviceDepth) {
                        inDevice = false; deviceDepth = -1
                    }
                    depth--; currentTag = ""
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isEmpty()) { event = parser.next(); continue }
                    when {
                        inDevice && !inService && currentTag.equals("UDN", ignoreCase = true)          && udn.isEmpty()   -> udn   = text
                        inDevice && !inService && currentTag.equals("friendlyName", ignoreCase = true) && name.isEmpty()  -> name  = text
                        inDevice && !inService && currentTag.equals("modelName", ignoreCase = true)    && model == null   -> model = text
                        inService && currentTag.equals("serviceType", ignoreCase = true)
                                && text.contains("AVTransport", ignoreCase = true)                                         -> hasAvTransport = true
                    }
                }
            }
            event = parser.next()
        }

        Log.i(TAG, "Parsed: udn=$udn name='$name' avTransport=$hasAvTransport @ $location")

        return if (hasAvTransport && udn.isNotBlank()) {
            DiscoveredRenderer(
                udn      = udn,
                name     = name.ifBlank { host },
                model    = model,
                location = location,
                host     = host
            )
        } else null
    }
}

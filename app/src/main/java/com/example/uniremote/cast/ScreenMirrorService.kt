package com.example.uniremote.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.uniremote.R
import com.example.uniremote.network.TransportSecurityPolicy
import com.example.uniremote.network.SoftwareTlsKey
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that captures display frames via MediaProjection and streams
 * H.264 Annex-B over HTTP with per-session access control.
 *
 * Security and lifecycle characteristics:
 * - Per-session high-entropy token authentication
 * - Session TTL enforcement for all incoming requests
 * - Optional client IP restriction policy
 * - Centralized client connection lifecycle and shutdown
 * - Encoder starts on first client and stops when no clients remain
 */
class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "com.example.uniremote.MIRROR_START"
        const val ACTION_STOP = "com.example.uniremote.MIRROR_STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val EXTRA_CLIENT_RESTRICTION_MODE = "client_restriction_mode"

        const val RESTRICTION_NONE = "none"
        const val RESTRICTION_SAME_SUBNET = "same_subnet"
        const val RESTRICTION_FIRST_CLIENT = "first_client"

        const val NOTIFICATION_CHANNEL_ID = "uniremote_mirror_channel"
        const val NOTIFICATION_ID = 2001

        const val STREAM_PORT = 8554
        const val STREAM_PATH = "/screen.h264"

        private const val TAG = "ScreenMirrorService"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

        /** A short-lived access token for each mirroring session. */
        const val SESSION_TTL_MS = 15 * 60 * 1000L

        /** Delay before stopping encoder when the last client disconnects. */
        private const val ENCODER_IDLE_TIMEOUT_MS = 7_000L

        /** UI callback set by ViewModel to track stop lifecycle. */
        @Volatile var onStopped: (() -> Unit)? = null
        @Volatile var onSessionStarted: ((
            publicEndpoint: String,
            authHeaderValue: String,
            maskedToken: String,
            tlsFingerprintSha256: String
        ) -> Unit)? = null

        @Volatile var isRunning: Boolean = false
        @Volatile var streamUrl: String? = null

        fun generateSessionToken(): String = StreamSessionAccessController.generateSessionToken()

        fun buildStreamEndpointUrl(ip: String, secure: Boolean): String {
            val scheme = if (secure) "https" else "http"
            return "$scheme://$ip:$STREAM_PORT$STREAM_PATH"
        }

        fun buildAuthorizationHeaderValue(token: String): String {
            return "Bearer $token"
        }

        fun maskToken(token: String): String {
            if (token.length <= 8) return "****"
            return "${token.take(4)}…${token.takeLast(4)}"
        }
    }

    private enum class EncoderState { STOPPED, RUNNING }

    private data class MirrorConfig(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val fps: Int,
        val bitrate: Int
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val encoderLock = Any()

    private val accessController = StreamSessionAccessController()
    private val requestSyncFrame = AtomicBoolean(false)

    private var tlsFingerprintSha256: String? = null

    private val failedAuthLimiter = RequestRateLimiter(maxEvents = 12, windowMs = 60_000L)
    private val connectionBurstLimiter = RequestRateLimiter(maxEvents = 18, windowMs = 10_000L)

    private var projection: MediaProjection? = null
    private var mirrorConfig: MirrorConfig? = null

    private var encoder: MediaCodec? = null
    private var encoderInputSurface: android.view.Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderDrainJob: Job? = null
    private var encoderIdleStopJob: Job? = null
    private var encoderState: EncoderState = EncoderState.STOPPED

    private var h264Server: AuthenticatedH264HttpServer? = null
    private var notificationEndpoint: String? = null
    private var sessionStartedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) {
                    Log.i(TAG, "New ACTION_START received while running, resetting previous session")
                    stopCurrentSession(reason = "restart")
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)
                val restrictionModeRaw = intent.getStringExtra(EXTRA_CLIENT_RESTRICTION_MODE)
                val restrictionMode = parseRestrictionMode(restrictionModeRaw)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.w(TAG, "ACTION_START rejected: invalid projection grant payload")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startMirroringSession(resultCode, resultData, sessionToken, restrictionMode)
            }

            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCurrentSession(reason = "service-destroy")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        onStopped?.invoke()
        onStopped = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMirroringSession(
        resultCode: Int,
        resultData: Intent,
        explicitToken: String?,
        restrictionMode: StreamSessionAccessController.ClientRestrictionMode
    ) {
        startForegroundForProjection()

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            Log.e(TAG, "Unable to obtain MediaProjection instance")
            stopSelf()
            return
        }

        projection = mediaProjection

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection revoked by system/user")
                    stopSelf()
                }
            }, null)
        }

        val config = buildMirrorConfig()
        mirrorConfig = config

        val localIp = getLocalIp()
        val networkPrefix = getActiveIpv4PrefixLength()
        val credentials = accessController.startSession(
            ttlMs = SESSION_TTL_MS,
            explicitToken = explicitToken,
            serverIp = localIp,
            restrictionMode = restrictionMode,
            serverPrefixLength = networkPrefix
        )

        val shownIp = localIp ?: "?.?.?.?"
        val server = AuthenticatedH264HttpServer(
            port = STREAM_PORT,
            streamPath = STREAM_PATH,
            accessController = accessController,
            failedAuthLimiter = failedAuthLimiter,
            connectionBurstLimiter = connectionBurstLimiter,
            onClientConnected = { id, ip, count ->
                serviceScope.launch { onClientConnected(id, ip, count) }
            },
            onClientDisconnected = { id, ip, count ->
                serviceScope.launch { onClientDisconnected(id, ip, count) }
            }
        )

        // For DLNA compatibility, prefer plaintext HTTP whenever insecure LAN transport is enabled.
        val useTlsTransport = !TransportSecurityPolicy.allowInsecureDlnaCasting()
        val tlsConfigured = if (useTlsTransport) {
            configureTransportSecurity(server)
        } else {
            false
        }
        if (useTlsTransport && !tlsConfigured) {
            Log.e(TAG, "Aborting session because HTTPS transport is required but unavailable")
            stopSelf()
            return
        }

        val endpoint = buildStreamEndpointUrl(shownIp, secure = useTlsTransport)
        notificationEndpoint = endpoint
        streamUrl = endpoint

        val serverStarted = runCatching {
            server.start(5_000, false)
            true
        }.getOrElse {
            Log.e(TAG, "Failed to start HTTP stream server", it)
            false
        }

        if (!serverStarted) {
            stopSelf()
            return
        }

        h264Server = server
        isRunning = true
        sessionStartedAtMs = System.currentTimeMillis()

        refreshNotification()
        onSessionStarted?.invoke(
            endpoint,
            buildAuthorizationHeaderValue(credentials.token),
            maskToken(credentials.token),
            tlsFingerprintSha256 ?: "unknown"
        )
        Log.i(
            TAG,
            "Mirroring session started. endpoint=$notificationEndpoint secure=$useTlsTransport ttlMs=$SESSION_TTL_MS restriction=$restrictionMode prefix=$networkPrefix tlsFp=${tlsFingerprintSha256?.take(12)}..."
        )
    }

    private fun onClientConnected(clientId: String, clientIp: String?, count: Int) {
        Log.i(TAG, "Client connected id=$clientId ip=$clientIp activeClients=$count")

        encoderIdleStopJob?.cancel()
        encoderIdleStopJob = null

        if (count == 1) {
            ensureEncoderStarted(reason = "first-client")
        }

        requestSyncFrame.set(true)
        refreshNotification()
    }

    private fun onClientDisconnected(clientId: String, clientIp: String?, count: Int) {
        Log.i(TAG, "Client disconnected id=$clientId ip=$clientIp activeClients=$count")

        if (count == 0) {
            scheduleEncoderIdleStop()
        }
    }

    private fun scheduleEncoderIdleStop() {
        encoderIdleStopJob?.cancel()
        encoderIdleStopJob = serviceScope.launch {
            kotlinx.coroutines.delay(ENCODER_IDLE_TIMEOUT_MS)
            if (h264Server?.activeClientCount() == 0) {
                stopEncoder(reason = "idle-timeout")
            }
        }
    }

    private fun ensureEncoderStarted(reason: String) {
        synchronized(encoderLock) {
            if (!isRunning) return
            if (encoderState == EncoderState.RUNNING) return

            val mediaProjection = projection
            val config = mirrorConfig
            if (mediaProjection == null || config == null) {
                Log.e(TAG, "Cannot start encoder: projection/config unavailable")
                return
            }

            val codec = runCatching {
                MediaCodec.createEncoderByType(MIME_AVC)
            }.getOrElse {
                Log.e(TAG, "createEncoderByType failed", it)
                return
            }

            val format = MediaFormat.createVideoFormat(MIME_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }

            val started = runCatching {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()

                val vd = mediaProjection.createVirtualDisplay(
                    "UniRemote_Mirror_H264",
                    config.width,
                    config.height,
                    config.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )

                if (vd == null) {
                    surface.release()
                    codec.stop()
                    codec.release()
                    false
                } else {
                    encoder = codec
                    encoderInputSurface = surface
                    virtualDisplay = vd
                    true
                }
            }.getOrElse {
                Log.e(TAG, "Failed to configure/start encoder", it)
                runCatching { codec.stop() }
                runCatching { codec.release() }
                false
            }

            if (!started) {
                return
            }

            encoderDrainJob?.cancel()
            encoderDrainJob = serviceScope.launch {
                drainEncoderLoop()
            }

            encoderState = EncoderState.RUNNING
            Log.i(TAG, "Encoder started reason=$reason")
        }
    }

    private fun stopEncoder(reason: String) {
        synchronized(encoderLock) {
            if (encoderState == EncoderState.STOPPED) return

            encoderDrainJob?.cancel()
            encoderDrainJob = null

            runCatching { virtualDisplay?.release() }
            virtualDisplay = null

            runCatching { encoderInputSurface?.release() }
            encoderInputSurface = null

            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null

            encoderState = EncoderState.STOPPED
            Log.i(TAG, "Encoder stopped reason=$reason")
        }
    }

    private suspend fun drainEncoderLoop() = withContext(Dispatchers.IO) {
        val mediaCodec = encoder ?: return@withContext
        val server = h264Server ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()
        var lastPeriodicSyncMs = 0L

        while (isActive && isRunning && encoderState == EncoderState.RUNNING) {
            if (requestSyncFrame.compareAndSet(true, false)) {
                requestSyncFrame(mediaCodec)
            }

            val now = System.currentTimeMillis()
            if (now - lastPeriodicSyncMs >= 2_500L) {
                requestSyncFrame(mediaCodec)
                lastPeriodicSyncMs = now
            }

            when (val index = mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = mediaCodec.outputFormat
                    val csd0 = outputFormat.getByteBuffer("csd-0")?.toByteArraySafe()
                    val csd1 = outputFormat.getByteBuffer("csd-1")?.toByteArraySafe()
                    val codecConfig = H264AnnexBConverter.buildCodecConfig(csd0, csd1)
                    if (codecConfig != null) {
                        server.updateCodecConfig(codecConfig)
                    }
                }

                else -> {
                    if (index < 0) continue

                    val outputBuffer = mediaCodec.getOutputBuffer(index)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val sample = ByteArray(bufferInfo.size)
                        outputBuffer.get(sample)

                        val annexB = H264AnnexBConverter.toAnnexB(sample)
                        when {
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                                server.updateCodecConfig(annexB)
                            }

                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> {
                                server.publishKeyFrame(annexB)
                            }

                            else -> {
                                server.publishFrame(annexB)
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun requestSyncFrame(mediaCodec: MediaCodec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        runCatching {
            mediaCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun buildMirrorConfig(): MirrorConfig {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        val (width, height) = computeCaptureSize(metrics.widthPixels, metrics.heightPixels)
        val fps = 30
        return MirrorConfig(
            width = width,
            height = height,
            dpi = metrics.densityDpi.coerceAtLeast(72),
            fps = fps,
            bitrate = computeBitrate(width, height, fps)
        )
    }

    private fun computeCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        val maxLongEdge = 1280
        val longEdge = maxOf(width, height)
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f

        val scaledWidth = (width * scale).toInt().coerceAtLeast(320)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(240)

        return Pair(scaledWidth and 0xFFFE, scaledHeight and 0xFFFE)
    }

    private fun computeBitrate(width: Int, height: Int, fps: Int): Int {
        val bitsPerPixelPerFrame = 0.10f
        return (width * height * fps * bitsPerPixelPerFrame)
            .toInt()
            .coerceIn(2_000_000, 10_000_000)
    }

    private fun stopCurrentSession(reason: String) {
        Log.i(TAG, "Stopping mirroring session reason=$reason")

        val sessionDurationMs = (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(1L)
        val telemetry = h264Server?.snapshotTelemetry()
        if (telemetry != null) {
            val kbps = ((telemetry.totalBytesSent * 8.0) / sessionDurationMs).toInt()
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            Log.i(
                TAG,
                "Session telemetry: durationMs=$sessionDurationMs packets=${telemetry.totalPacketsSent} bytes=${telemetry.totalBytesSent} avgKbps=$kbps authReject=${telemetry.authRejected} burstReject=${telemetry.burstRejected} overloadReject=${telemetry.overloadRejected} activeClients=${telemetry.activeClients} usedMemMb=$usedMb"
            )
        }

        isRunning = false
        streamUrl = null
        notificationEndpoint = null
        tlsFingerprintSha256 = null
        sessionStartedAtMs = 0L

        encoderIdleStopJob?.cancel()
        encoderIdleStopJob = null

        stopEncoder(reason = "session-stop")

        runCatching { h264Server?.shutdown("session-stop") }
        runCatching { h264Server?.stop() }
        h264Server = null

        runCatching { projection?.stop() }
        projection = null

        mirrorConfig = null

        accessController.invalidateSession()
        failedAuthLimiter.clear()
        connectionBurstLimiter.clear()
    }

    private fun startForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.cast_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenMirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.cast_notification_title))
            .setContentText(notificationEndpoint ?: getString(R.string.cast_notification_starting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.cast_notification_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val updated = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.cast_notification_title))
            .setContentText(notificationEndpoint ?: getString(R.string.cast_notification_starting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.cast_notification_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ScreenMirrorService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, updated)
    }

    private fun parseRestrictionMode(mode: String?): StreamSessionAccessController.ClientRestrictionMode {
        return when (mode?.lowercase(Locale.US)) {
            RESTRICTION_NONE -> StreamSessionAccessController.ClientRestrictionMode.NONE
            RESTRICTION_FIRST_CLIENT -> StreamSessionAccessController.ClientRestrictionMode.FIRST_CLIENT
            RESTRICTION_SAME_SUBNET -> StreamSessionAccessController.ClientRestrictionMode.SAME_SUBNET
            else -> StreamSessionAccessController.ClientRestrictionMode.FIRST_CLIENT
        }
    }

    private fun configureTransportSecurity(server: AuthenticatedH264HttpServer): Boolean {
        val bundle = buildTlsServerSocketFactory() ?: return false
        tlsFingerprintSha256 = bundle.fingerprintSha256
        server.makeSecure(bundle.factory, null)
        Log.i(TAG, "HTTPS transport enabled. tlsFp=${bundle.fingerprintSha256.take(12)}...")
        return true
    }

    private data class TlsServerBundle(
        val factory: SSLServerSocketFactory,
        val fingerprintSha256: String
    )

    private fun buildTlsServerSocketFactory(): TlsServerBundle? {
        return runCatching {
            SoftwareTlsKey.init(applicationContext)
            val (privateKey, cert) = SoftwareTlsKey.getOrCreate()

            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

            val passphrase = "mirror_ssl".toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, passphrase)
                setKeyEntry("mirror", privateKey, passphrase, arrayOf(cert))
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, passphrase)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, SecureRandom())
            TlsServerBundle(sslContext.serverSocketFactory, fingerprint)
        }.getOrElse {
            Log.e(TAG, "Failed to create TLS server socket factory", it)
            null
        }
    }

    private fun getActiveIpv4PrefixLength(): Int? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return null
        return cm.getLinkProperties(active)
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.prefixLength
    }

    private fun getLocalIp(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val fromActive = cm.getLinkProperties(activeNetwork)
                ?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?.lowercase(Locale.US)
            if (!fromActive.isNullOrBlank()) {
                return fromActive
            }
        }

        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress?.lowercase(Locale.US)
                }
            }
        }
        return null
    }

    /**
     * HTTP server with centralized connection lifecycle management.
     */
    private class AuthenticatedH264HttpServer(
        port: Int,
        private val streamPath: String,
        private val accessController: StreamSessionAccessController,
        private val failedAuthLimiter: RequestRateLimiter,
        private val connectionBurstLimiter: RequestRateLimiter,
        private val onClientConnected: (clientId: String, clientIp: String?, count: Int) -> Unit,
        private val onClientDisconnected: (clientId: String, clientIp: String?, count: Int) -> Unit
    ) : NanoHTTPD(port) {

        companion object {
            private const val LOG_TAG = "H264HttpServer"
            private const val MAX_CONCURRENT_CLIENTS = 4
        }

        private data class ClientConnection(
            val id: String,
            val ip: String?,
            val queue: ArrayBlockingQueue<ByteArray>,
            val output: PipedOutputStream,
            val worker: Thread,
            val active: AtomicBoolean = AtomicBoolean(true)
        )

        data class StreamTelemetry(
            val totalBytesSent: Long,
            val totalPacketsSent: Long,
            val authRejected: Long,
            val burstRejected: Long,
            val overloadRejected: Long,
            val activeClients: Int
        )

        private val accepting = AtomicBoolean(true)
        private val clients = ConcurrentHashMap<String, ClientConnection>()
        private val totalBytesSent = AtomicLong(0L)
        private val totalPacketsSent = AtomicLong(0L)
        private val authRejected = AtomicLong(0L)
        private val burstRejected = AtomicLong(0L)
        private val overloadRejected = AtomicLong(0L)

        @Volatile
        private var codecConfig: ByteArray? = null

        fun activeClientCount(): Int = clients.size

        fun snapshotTelemetry(): StreamTelemetry {
            return StreamTelemetry(
                totalBytesSent = totalBytesSent.get(),
                totalPacketsSent = totalPacketsSent.get(),
                authRejected = authRejected.get(),
                burstRejected = burstRejected.get(),
                overloadRejected = overloadRejected.get(),
                activeClients = clients.size
            )
        }

        fun updateCodecConfig(config: ByteArray) {
            codecConfig = config
        }

        fun publishFrame(frame: ByteArray) {
            broadcast(frame)
        }

        fun publishKeyFrame(frame: ByteArray) {
            codecConfig?.let { broadcast(it) }
            broadcast(frame)
        }

        private fun broadcast(packet: ByteArray) {
            totalPacketsSent.incrementAndGet()
            totalBytesSent.addAndGet(packet.size.toLong())
            for (client in clients.values) {
                val q = client.queue
                if (!q.offer(packet)) {
                    q.poll()
                    q.offer(packet)
                }
            }
        }

        fun shutdown(reason: String) {
            accepting.set(false)
            closeAllClients(reason)
            Log.i(LOG_TAG, "Server shutdown requested reason=$reason")
        }

        override fun serve(session: IHTTPSession): Response {
            val clientIp = resolveClientIp(session)
            val burstKey = clientIp ?: "unknown"

            if (!connectionBurstLimiter.allow("conn:$burstKey")) {
                burstRejected.incrementAndGet()
                Log.w(LOG_TAG, "Connection burst limited ip=$clientIp")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Too many connection attempts"
                )
            }

            val credential = extractCredential(session)
            val validation = accessController.validate(
                StreamSessionAccessController.AccessRequest(
                    credential = credential,
                    clientIp = clientIp
                )
            )

            if (validation != StreamSessionAccessController.ValidationResult.Allowed) {
                Log.w(LOG_TAG, "Auth denied from ip=$clientIp reason=$validation")
                val authKey = clientIp ?: "unknown"
                val allowedRetry = failedAuthLimiter.allow("auth:$authKey")
                authRejected.incrementAndGet()
                return newFixedLengthResponse(
                    if (allowedRetry) Response.Status.UNAUTHORIZED else Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    if (allowedRetry) "Unauthorized" else "Too many failed auth attempts"
                )
            }

            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    MIME_PLAINTEXT,
                    "Only GET and HEAD allowed"
                )
            }

            if (!accepting.get()) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Service is stopping"
                )
            }

            if (session.uri != streamPath) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }

            if (clients.size >= MAX_CONCURRENT_CLIENTS) {
                overloadRejected.incrementAndGet()
                Log.w(LOG_TAG, "Max clients reached (${clients.size})")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Server is at max capacity"
                )
            }

            val transferMode = session.headers["transfermode.dlna.org"]
                ?: session.headers["TransferMode.DLNA.ORG"]

            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(Response.Status.OK, "video/avc", "").apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("Connection", "close")
                    addHeader("Pragma", "no-cache")
                    addHeader("Accept-Ranges", "none")
                    if (!transferMode.isNullOrBlank()) {
                        addHeader("TransferMode.DLNA.ORG", transferMode)
                    }
                }
            }

            return openClientStream(clientIp, transferMode)
        }

        private fun openClientStream(clientIp: String?, transferMode: String?): Response {
            val clientId = UUID.randomUUID().toString().take(8)
            val queue = ArrayBlockingQueue<ByteArray>(64)
            val output = PipedOutputStream()
            val input = PipedInputStream(output, 2 * 1024 * 1024)

            val worker = Thread {
                try {
                    while (accepting.get()) {
                        val connection = clients[clientId] ?: break
                        if (!connection.active.get()) break
                        if (!accessController.hasActiveSession()) break

                        val packet = queue.poll(2, TimeUnit.SECONDS) ?: continue
                        accessController.touchSession()
                        output.write(packet)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // Client disconnected or server shutdown.
                } finally {
                    removeClient(clientId, reason = "worker-terminated")
                    runCatching { output.close() }
                }
            }.apply {
                name = "h264-client-$clientId"
                isDaemon = true
            }

            val connection = ClientConnection(
                id = clientId,
                ip = clientIp,
                queue = queue,
                output = output,
                worker = worker
            )

            clients[clientId] = connection
            codecConfig?.let { queue.offer(it) }
            worker.start()

            onClientConnected(clientId, clientIp, clients.size)

            return newChunkedResponse(Response.Status.OK, "video/avc", input).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Connection", "close")
                addHeader("Pragma", "no-cache")
                addHeader("Accept-Ranges", "none")
                if (!transferMode.isNullOrBlank()) {
                    addHeader("TransferMode.DLNA.ORG", transferMode)
                }
            }
        }

        private fun removeClient(clientId: String, reason: String) {
            val removed = clients.remove(clientId) ?: return
            if (removed.active.compareAndSet(true, false)) {
                runCatching { removed.output.close() }
                runCatching { removed.worker.interrupt() }
                Log.i(LOG_TAG, "Client removed id=$clientId ip=${removed.ip} reason=$reason")
                onClientDisconnected(clientId, removed.ip, clients.size)
            }
        }

        private fun closeAllClients(reason: String) {
            val ids = clients.keys.toList()
            for (id in ids) {
                removeClient(id, reason)
            }
        }

        private fun resolveClientIp(session: IHTTPSession): String? {
            return runCatching { session.remoteIpAddress }.getOrNull()
        }

        private fun extractCredential(session: IHTTPSession): String? {
            val authHeader = session.headers["authorization"] ?: session.headers["Authorization"]
            if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                val bearer = authHeader.substringAfter(' ').trim()
                if (bearer.isNotBlank()) return bearer
            }
            return null
        }
    }
}

private fun java.nio.ByteBuffer.toByteArraySafe(): ByteArray {
    val dup = duplicate()
    dup.clear()
    val out = ByteArray(dup.remaining())
    dup.get(out)
    return out
}

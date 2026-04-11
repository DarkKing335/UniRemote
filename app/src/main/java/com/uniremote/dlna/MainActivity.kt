package com.uniremote.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.uniremote.dlna.dlna.DlnaManager
import com.uniremote.dlna.dlna.DlnaRenderer
import com.uniremote.dlna.dlna.DlnaUpnpService
import com.uniremote.dlna.dlna.NanoHttpMediaServer
import com.example.uniremote.R
import org.jupnp.android.AndroidUpnpService
import org.jupnp.support.model.PositionInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mediaText: TextView
    private lateinit var rendererList: ListView
    private lateinit var btnDiscover: Button
    private lateinit var btnPickMedia: Button
    private lateinit var btnCast: Button
    private lateinit var btnStop: Button
    private lateinit var btnPosition: Button

    private var selectedMedia: Uri? = null
    private var selectedMediaName: String = ""
    private var selectedRenderer: DlnaRenderer? = null
    private var selectedToken: String? = null

    private var upnpService: AndroidUpnpService? = null
    private var mediaServer: NanoHttpMediaServer? = null

    private val renderers = mutableListOf<DlnaRenderer>()
    private lateinit var adapter: ArrayAdapter<String>

    private val dlnaManager = DlnaManager(
        onDevicesChanged = { devices ->
            runOnUiThread {
                renderers.clear()
                renderers.addAll(devices)
                adapter.clear()
                adapter.addAll(devices.map { formatRendererLabel(it) })
                adapter.notifyDataSetChanged()
                statusText.text = "Found ${devices.size} renderer(s)"
            }
        },
        onError = { message ->
            runOnUiThread { statusText.text = message }
        },
        onPosition = { info: PositionInfo ->
            runOnUiThread {
                statusText.text = "Position: ${info.relTime} / ${info.trackDuration}"
            }
        }
    )

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            statusText.text = "No media selected"
            return@registerForActivityResult
        }

        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        selectedMedia = uri
        selectedMediaName = queryDisplayName(uri) ?: "media-${System.currentTimeMillis()}"
        mediaText.text = selectedMediaName
        statusText.text = "Media selected"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val androidService = service as? AndroidUpnpService
            if (androidService == null) {
                statusText.text = "DLNA service bind failed"
                return
            }

            upnpService = androidService
            dlnaManager.bind(androidService)
            statusText.text = "DLNA service connected"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dlnaManager.unbind()
            upnpService = null
            statusText.text = "DLNA service disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.txtStatus)
        mediaText = findViewById(R.id.txtMedia)
        rendererList = findViewById(R.id.listRenderers)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnPickMedia = findViewById(R.id.btnPickMedia)
        btnCast = findViewById(R.id.btnCast)
        btnStop = findViewById(R.id.btnStop)
        btnPosition = findViewById(R.id.btnPosition)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf())
        rendererList.choiceMode = ListView.CHOICE_MODE_SINGLE
        rendererList.adapter = adapter

        rendererList.setOnItemClickListener { _, _, position, _ ->
            selectedRenderer = renderers.getOrNull(position)
            statusText.text = selectedRenderer?.let { "Selected: ${it.name}" } ?: "No renderer selected"
        }

        btnDiscover.setOnClickListener {
            dlnaManager.refresh()
            statusText.text = "Discovering DLNA renderers..."
        }

        btnPickMedia.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*", "audio/*"))
        }

        btnCast.setOnClickListener { castSelectedMedia() }

        btnStop.setOnClickListener {
            selectedRenderer?.let {
                dlnaManager.stop(it.udn)
                statusText.text = "Stop command sent"
            } ?: run {
                statusText.text = "Select renderer first"
            }
        }

        btnPosition.setOnClickListener {
            selectedRenderer?.let {
                dlnaManager.fetchPosition(it.udn)
            } ?: run {
                statusText.text = "Select renderer first"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DlnaUpnpService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        if (mediaServer == null) {
            mediaServer = NanoHttpMediaServer(this)
            mediaServer?.start(5000, false)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(serviceConnection) }
        dlnaManager.unbind()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaServer?.stop()
        mediaServer = null
    }

    private fun castSelectedMedia() {
        val renderer = selectedRenderer
        if (renderer == null) {
            statusText.text = "Select renderer first"
            return
        }

        val mediaUri = selectedMedia
        if (mediaUri == null) {
            statusText.text = "Pick a media file first"
            return
        }

        val server = mediaServer
        if (server == null) {
            statusText.text = "Media server not available"
            return
        }

        val host = localIpv4Address()
        if (host.isNullOrBlank()) {
            statusText.text = "Cannot resolve local IP. Ensure phone and TV are in same Wi-Fi"
            return
        }

        val mime = contentResolver.getType(mediaUri) ?: "video/mp4"
        val token = server.addMedia(mediaUri, mime, selectedMediaName)
        selectedToken?.let { server.removeRoute(it) }
        selectedToken = token

        val mediaUrl = "http://$host:8080/$token"
        dlnaManager.cast(renderer.udn, mediaUrl, selectedMediaName)
        statusText.text = "Casting to ${renderer.name}..."
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun localIpv4Address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) {
                continue
            }
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

    private fun formatRendererLabel(renderer: DlnaRenderer): String {
        return if (TextUtils.isEmpty(renderer.model)) {
            renderer.name
        } else {
            "${renderer.name} (${renderer.model})"
        }
    }
}

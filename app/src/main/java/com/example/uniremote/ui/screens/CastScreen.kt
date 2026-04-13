package com.example.uniremote.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastState
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.viewmodel.RemoteViewModel
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlin.math.roundToInt

// ─── Helpers ──────────────────────────────────────────────────────────────────

internal fun handleNotificationPermissionResult(
    granted: Boolean,
    onGranted: () -> Unit,
    onDenied: () -> Unit
) {
    if (granted) onGranted() else onDenied()
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%02d:%02d".format(total / 60, total % 60)
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

@Composable
fun CastScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    val castRenderers    by vm.castRenderers.collectAsStateWithLifecycle()
    val castState        by vm.castState.collectAsStateWithLifecycle()
    val castPlaybackInfo by vm.castPlaybackInfo.collectAsStateWithLifecycle()
    val isMirroring      by vm.isMirroring.collectAsStateWithLifecycle()
    val mirrorStreamUrl  by vm.mirrorStreamUrl.collectAsStateWithLifecycle()
    val selectedUdn      by vm.selectedCastRendererUdn.collectAsStateWithLifecycle()
    val selectedName     by vm.selectedCastRendererName.collectAsStateWithLifecycle()
    var pendingMirrorRenderer by remember { mutableStateOf<Pair<String, String>?>(null) }

    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    val isDiscovering  = castState is CastState.Discovering
    val isCasting      = castState is CastState.Casting
    val isBusy         = isCasting || castState is CastState.SendingUri || castState is CastState.StartingPlayback
    val castError      = (castState as? CastState.Error)?.message
    val hasRenderer    = selectedUdn != null

    // ── File picker – image ────────────────────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val udn = selectedUdn ?: run {
            Toast.makeText(context, "Vui lòng chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val mime  = context.contentResolver.getType(uri) ?: "image/*"
        val title = queryDisplayName(context, uri) ?: "image"
        vm.castMedia(
            uri = uri,
            mimeType = mime,
            title = title,
            rendererUdn = udn,
            rendererName = selectedName ?: "Cast Device"
        )
    }

    // ── File picker – video ────────────────────────────────────────────────────
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val udn = selectedUdn ?: run {
            Toast.makeText(context, "Vui lòng chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val mime  = context.contentResolver.getType(uri) ?: "video/*"
        val title = queryDisplayName(context, uri) ?: "video"
        vm.castMedia(
            uri = uri,
            mimeType = mime,
            title = title,
            rendererUdn = udn,
            rendererName = selectedName ?: "Cast Device"
        )
    }

    // ── MediaProjection launcher ───────────────────────────────────────────────
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val mirrorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let {
                val target = pendingMirrorRenderer
                pendingMirrorRenderer = null
                // Pass renderer so mirroring auto-pushes stream URL via selected cast protocol
                vm.startMirroring(
                    resultCode   = result.resultCode,
                    data         = it,
                    rendererUdn  = target?.first ?: selectedUdn,
                    rendererName = target?.second ?: selectedName
                )
            }
        } else {
            pendingMirrorRenderer = null
        }
    }

    // ── POST_NOTIFICATIONS permission (Android 13+, for mirroring foreground service) ──
    val notifPermLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleNotificationPermissionResult(
                granted   = granted,
                onGranted = { mirrorLauncher.launch(projectionManager.createScreenCaptureIntent()) },
                onDenied  = {
                    vm.onMirroringPermissionDenied()
                    Toast.makeText(context, "Cần quyền thông báo để phản chiếu màn hình", Toast.LENGTH_SHORT).show()
                }
            )
        }
    } else null

    // ── NEARBY_WIFI_DEVICES permission is NOT needed for LAN SSDP discovery.
    // ── doScan: just rebind (if needed) and trigger SSDP discovery sweep.
    fun doScan() {
        vm.bindCastService()     // no-op if already bound; ensures service is up
        vm.refreshCastDevices()  // triggers SSDP M-SEARCH probe
    }

    fun doStartMirror() {
        fun launchAppMirroringFlow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                mirrorLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        if (selectedUdn?.startsWith("gcast:") == true) {
            if (castState is CastState.Error) {
                vm.stopCast()
            }

            val hasGoogleCastError = castState is CastState.Error
            if (!hasGoogleCastError) {
                val intents = listOf(
                    Intent("android.settings.CAST_SETTINGS"),
                    Intent(Settings.ACTION_SETTINGS),
                    Intent("com.android.settings.WIFI_DISPLAY_SETTINGS")
                )

                val opened = intents.firstOrNull { intent ->
                    runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                } != null

                if (opened) {
                    Toast.makeText(
                        context,
                        "Đã mở Cast màn hình hệ thống cho Google Cast.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            val fallback = vm.findDlnaFallbackRenderer(selectedName)
            if (fallback == null) {
                Toast.makeText(
                    context,
                    "Google Cast không khả dụng và chưa tìm thấy DLNA để fallback.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            vm.selectCastRenderer(fallback.udn, fallback.name)
            pendingMirrorRenderer = fallback.udn to fallback.name
            Toast.makeText(
                context,
                "Google Cast không hỗ trợ/lỗi, đã chuyển sang DLNA để auto-push stream.",
                Toast.LENGTH_LONG
            ).show()
        }

        launchAppMirroringFlow()
    }

    // Keep discovery warm globally so switching tabs does not reset cast list.
    LaunchedEffect(Unit) {
        vm.ensureCastDiscoveryStarted()
    }

    Scaffold(
        topBar    = { TopBar(title = "CAST", onPowerClick = { vm.power() }) },
        bottomBar = { BottomNavBar(currentTab = NavigationTab.CAST, onTabSelected = onNavigate) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {

            // ── Tip ──────────────────────────────────────────────────────────
            Text(
                text  = "Tip: Chromecast dùng Cast màn hình hệ thống; DLNA dùng mirroring trực tiếp trong app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // ── Error banner ──────────────────────────────────────────────────
            if (castError != null) {
                ErrorBanner(message = castError, modifier = Modifier.padding(bottom = 12.dp))
            }

            // ── Connect Cast Session ──────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    if (isMirroring) vm.stopMirroring()
                    else if (!hasRenderer) {
                        Toast.makeText(context, "Chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
                    } else {
                        doStartMirror()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.Cast, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMirroring) "Dừng Cast Session" else "Connect Cast Session",
                    fontWeight = FontWeight.Medium
                )
            }

            // ── NEARBY DEVICES header ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "NEARBY DEVICES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isDiscovering) { doScan() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Scan",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isDiscovering) "SCANNING..." else "SCAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider()

            // ── Device list ───────────────────────────────────────────────────
            if (castRenderers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isDiscovering)
                            "Đang tìm kiếm thiết bị Cast (DLNA/Chromecast)..."
                        else
                            "Không tìm thấy thiết bị Cast (DLNA/Chromecast).\nĐảm bảo TV và điện thoại cùng Wi-Fi,\nrồi bấm SCAN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            } else {
                Column {
                    castRenderers.forEach { renderer ->
                        val isSelected = renderer.udn == selectedUdn
                        val isActivelyCasting = isCasting && isSelected
                        RendererRow(
                            renderer  = renderer,
                            isSelected = isSelected,
                            isCasting  = isActivelyCasting,
                            enabled    = !isBusy,
                            onClick    = {
                                vm.selectCastRenderer(renderer.udn, renderer.name)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Active Cast Playback Controls ─────────────────────────────────
            if (isCasting) {
                val state = castState as? CastState.Casting
                PlaybackCard(
                    title        = state?.title ?: "",
                    rendererName = state?.rendererName ?: "",
                    info         = castPlaybackInfo,
                    onStop       = { vm.stopCast() },
                    onPlay       = { vm.playCast() },
                    onPause      = { vm.pauseCast() },
                    onSeekBack   = { vm.seekCastBy(-10_000L) },
                    onSeekForward = { vm.seekCastBy(10_000L) },
                    onSeekTo     = { vm.seekCastTo(it) },
                    modifier     = Modifier.padding(bottom = 16.dp)
                )
            }

            // ── Mirror Stream URL (when mirroring active) ─────────────────────
            if (isMirroring && mirrorStreamUrl != null) {
                MirrorUrlCard(
                    url      = mirrorStreamUrl!!,
                    onCopy   = {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Stream URL", mirrorStreamUrl))
                        Toast.makeText(context, "Đã copy URL stream", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // ── QUICK ACTIONS ─────────────────────────────────────────────────
            Text(
                text = "QUICK ACTIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Screen Mirroring – full width
            QuickActionTile(
                icon    = Icons.Filled.Smartphone,
                label   = if (isMirroring) "Dừng Mirroring" else "Screen Mirroring",
                subLabel = when {
                    isMirroring -> "Đang phản chiếu màn hình"
                    selectedUdn?.startsWith("gcast:") == true -> "Google Cast: ưu tiên Cast màn hình hệ thống"
                    hasRenderer -> "Phản chiếu lên: ${selectedName ?: "Cast Device"}"
                    else        -> "Chọn thiết bị Cast trước"
                },
                active  = isMirroring,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                onClick = {
                    if (isMirroring) vm.stopMirroring()
                    else if (!hasRenderer) Toast.makeText(context, "Chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
                    else doStartMirror()
                }
            )

            // Cast Image & Cast Video
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionTile(
                    icon     = Icons.Filled.Image,
                    label    = "Cast Image",
                    subLabel = if (hasRenderer) (selectedName ?: "Cast Device") else "Chọn TV trước",
                    active   = false,
                    modifier = Modifier.weight(1f),
                    onClick  = {
                        if (!hasRenderer) Toast.makeText(context, "Chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
                        else imagePicker.launch(arrayOf("image/*"))
                    }
                )
                QuickActionTile(
                    icon     = Icons.Filled.VideoFile,
                    label    = "Cast Video",
                    subLabel = if (hasRenderer) (selectedName ?: "Cast Device") else "Chọn TV trước",
                    active   = isCasting,
                    modifier = Modifier.weight(1f),
                    onClick  = {
                        if (!hasRenderer) Toast.makeText(context, "Chọn thiết bị Cast trước", Toast.LENGTH_SHORT).show()
                        else videoPicker.launch(arrayOf("video/*"))
                    }
                )
            }

            // ── Wi-Fi hint ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text  = "TV và điện thoại phải cùng mạng Wi-Fi",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─── Renderer Row ─────────────────────────────────────────────────────────────

@Composable
private fun RendererRow(
    renderer: DlnaRenderer,
    isSelected: Boolean,
    isCasting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else Color.Transparent
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Tv,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = renderer.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!renderer.model.isNullOrBlank()) {
                Text(
                    text = renderer.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val badge = when {
            isCasting  -> "CASTING"   to MaterialTheme.colorScheme.tertiary
            isSelected -> "CONNECTED" to MaterialTheme.colorScheme.primary
            else       -> null
        }
        if (badge != null) {
            Text(
                text = badge.first,
                style = MaterialTheme.typography.labelSmall,
                color = badge.second,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badge.second.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// ─── Playback Card ────────────────────────────────────────────────────────────

@Composable
private fun PlaybackCard(
    title: String,
    rendererName: String,
    info: CastPlaybackInfo,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val durationMs = info.durationMs.coerceAtLeast(0L)
    var isSeeking  by remember { mutableStateOf(false) }
    var seekValue  by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(info.positionMs, isSeeking) {
        if (!isSeeking) seekValue = info.positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title + Stop button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "▶ Casting to $rendererName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
        }

        // Progress
        if (durationMs > 0L) {
            Text(
                text = "${formatMs(info.positionMs)} / ${formatMs(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = seekValue,
                onValueChange = { isSeeking = true; seekValue = it },
                onValueChangeFinished = { isSeeking = false; onSeekTo(seekValue.toLong()) },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaybackBtn(Icons.Filled.FastRewind, "−10s", onSeekBack)
            PlaybackBtn(Icons.Filled.PlayArrow, "Play", onPlay)
            PlaybackBtn(Icons.Filled.Pause, "Pause", onPause)
            PlaybackBtn(Icons.Filled.FastForward, "+10s", onSeekForward)
        }
    }
}

@Composable
private fun PlaybackBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

// ─── Mirror URL Card ──────────────────────────────────────────────────────────

@Composable
private fun MirrorUrlCard(
    url: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "📡 Đang phản chiếu màn hình",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Nếu TV không tự mở, copy URL bên dưới và dán vào trình duyệt của TV:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.ContentCopy, "Copy",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─── Error Banner ─────────────────────────────────────────────────────────────

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

// ─── Quick Action Tile ────────────────────────────────────────────────────────

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    subLabel: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg   = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg   = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val fgSub = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(28.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = subLabel,
            style = MaterialTheme.typography.labelSmall,
            color = fgSub,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

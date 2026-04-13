package com.example.uniremote.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uniremote.R
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastState
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.theme.GlassBtnBorder
import com.example.uniremote.ui.theme.PremiumBgEnd
import com.example.uniremote.ui.theme.PremiumBgStart
import com.example.uniremote.ui.theme.primary_fixed_dim
import com.example.uniremote.ui.theme.surface_bright
import com.example.uniremote.ui.theme.surface_container_high
import com.example.uniremote.ui.theme.surface_container_low
import com.example.uniremote.ui.theme.surface_container_lowest
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

private fun formatCastTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun CastScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    val castRenderers  by vm.castRenderers.collectAsStateWithLifecycle()
    val castState      by vm.castState.collectAsStateWithLifecycle()
    val castPlaybackInfo by vm.castPlaybackInfo.collectAsStateWithLifecycle()
    val isMirroring    by vm.isMirroring.collectAsStateWithLifecycle()
    val mirrorStreamUrl by vm.mirrorStreamUrl.collectAsStateWithLifecycle()
    val mirrorAuthHint by vm.mirrorAuthHint.collectAsStateWithLifecycle()
    val mirrorTlsFingerprint by vm.mirrorTlsFingerprint.collectAsStateWithLifecycle()

    var selectedMediaUrl     by remember { mutableStateOf("") }
    var selectedUri          by remember { mutableStateOf<Uri?>(null) }
    var selectedMime         by remember { mutableStateOf("") }
    var selectedName         by remember { mutableStateOf("") }
    var selectedRendererUdn  by remember { mutableStateOf<String?>(null) }
    var selectedRendererName by remember { mutableStateOf("") }

    val context  = LocalContext.current
    val haptic   = LocalHapticFeedback.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    // ── File picker (SAF, no storage permission) ────────────────────────────
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        selectedUri = uri
        selectedMime = context.contentResolver.getType(uri) ?: "video/*"
        selectedName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: "media_${System.currentTimeMillis()}"
    }

    // ── MediaProjection launcher ─────────────────────────────────────────────
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val mirrorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let {
                vm.startMirroring(
                    resultCode = result.resultCode,
                    data = it,
                    rendererUdn = selectedRendererUdn,
                    rendererName = selectedRendererName
                )
            }
        }
    }

    // ── Notification permission (Android 13+) ────────────────────────────────
    val notifPermLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            handleNotificationPermissionResult(
                granted = granted,
                onGranted = {
                    mirrorLauncher.launch(projectionManager.createScreenCaptureIntent())
                },
                onDenied = {
                    vm.onMirroringPermissionDenied()
                    Toast.makeText(
                        context,
                        context.getString(R.string.cast_notification_permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    } else null

    // ── Auto-bind DLNA service while Cast screen is visible ──────────────────
    DisposableEffect(Unit) {
        vm.bindCastService()
        onDispose { vm.unbindCastService() }
    }

    Scaffold(
        topBar = {
            TopBar(title = stringResource(R.string.cast_screen_title), onPowerClick = { vm.power() })
        },
        bottomBar = {
            BottomNavBar(currentTab = NavigationTab.CAST, onTabSelected = onNavigate)
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(PremiumBgStart, PremiumBgEnd)))
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // ── Media Casting ──────────────────────────────────────────
                MediaCastingSection(
                    castState           = castState,
                    playbackInfo        = castPlaybackInfo,
                    renderers           = castRenderers,
                    selectedRendererUdn = selectedRendererUdn,
                    selectedMediaUrl    = selectedMediaUrl,
                    selectedFileName    = selectedName,
                    hasSelectedFile     = selectedUri != null,
                    onScanClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.refreshCastDevices()
                    },
                    onRendererSelect = { udn, name ->
                        selectedRendererUdn  = udn
                        selectedRendererName = name
                    },
                    onMediaUrlChange = {
                        selectedMediaUrl = it
                    },
                    onPickFile = {
                        filePicker.launch(arrayOf("video/*", "image/*"))
                    },
                    onCast = {
                        val udn = selectedRendererUdn ?: return@MediaCastingSection
                        val selectedLocalUri = selectedUri
                        val mediaUrl = selectedMediaUrl.trim()
                        val hasValidHttpUrl = mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")

                        if (selectedLocalUri != null && !hasValidHttpUrl) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.castMedia(
                                uri = selectedLocalUri,
                                mimeType = selectedMime,
                                title = selectedName.ifBlank { "Media" },
                                rendererUdn = udn,
                                rendererName = selectedRendererName
                            )
                            return@MediaCastingSection
                        }

                        if (!hasValidHttpUrl) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.cast_enter_valid_http_url),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MediaCastingSection
                        }

                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.castMedia(
                            uri = Uri.parse(mediaUrl),
                            mimeType = "",
                            title = mediaUrl.substringAfterLast('/').ifBlank { "Media" },
                            rendererUdn = udn,
                            rendererName = selectedRendererName
                        )
                    },
                    onStop = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.stopCast()
                    },
                    onPlay = { vm.playCast() },
                    onPause = { vm.pauseCast() },
                    onSeekBack = { vm.seekCastBy(-10_000L) },
                    onSeekForward = { vm.seekCastBy(10_000L) },
                    onSeekTo = { vm.seekCastTo(it) },
                    onVolumeDown = { vm.changeCastVolumeBy(-5) },
                    onVolumeUp = { vm.changeCastVolumeBy(5) },
                    onSetVolume = { vm.setCastVolume(it) },
                    onMuteToggle = { vm.toggleCastMute() }
                )

                // ── Screen Mirroring ───────────────────────────────────────
                MirroringSection(
                    isMirroring         = isMirroring,
                    streamUrl           = mirrorStreamUrl,
                    authHint            = mirrorAuthHint,
                    tlsFingerprint      = mirrorTlsFingerprint,
                    onStartMirroring = {
                        if (selectedRendererUdn.isNullOrBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.cast_select_tv_before_mirroring),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MirroringSection
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermLauncher?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            mirrorLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    onStopMirroring = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.stopMirroring()
                    },
                    onCopyUrl = { url ->
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(context, context.getString(R.string.cast_endpoint_copied), Toast.LENGTH_SHORT).show()
                    },
                    onCopySecureLink = {
                        val authHeader = vm.getMirrorAuthorizationHeaderForManualShare()
                        if (authHeader.isNullOrBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.cast_secure_link_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MirroringSection
                        }
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Authorization Header", authHeader))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(
                            context,
                            context.getString(R.string.cast_auth_header_copied_warning),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )

                // Wi-Fi hint footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.cast_wifi_hint),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ─── Media Casting Section ────────────────────────────────────────────────────

@Composable
private fun MediaCastingSection(
    castState: CastState,
    playbackInfo: CastPlaybackInfo,
    renderers: List<DlnaRenderer>,
    selectedRendererUdn: String?,
    selectedMediaUrl: String,
    selectedFileName: String,
    hasSelectedFile: Boolean,
    onScanClick: () -> Unit,
    onRendererSelect: (String, String) -> Unit,
    onMediaUrlChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onCast: () -> Unit,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onMuteToggle: () -> Unit
) {
    val isCasting     = castState is CastState.Casting
    val isSendingUri  = castState is CastState.SendingUri
    val isStarting    = castState is CastState.StartingPlayback
    val isDiscovering = castState is CastState.Discovering
    val isBusy        = isCasting || isSendingUri || isStarting
    val errorMsg      = (castState as? CastState.Error)?.message

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Section header
        CastSectionHeader(
            icon  = Icons.Filled.Cast,
            title = stringResource(R.string.cast_media_title),
            badgeText = when {
                isCasting     -> stringResource(R.string.cast_streaming)
                isSendingUri  -> stringResource(R.string.cast_preparing)
                isStarting    -> stringResource(R.string.cast_starting)
                isDiscovering -> stringResource(R.string.cast_scanning)
                renderers.isNotEmpty() -> stringResource(R.string.cast_ready)
                else          -> stringResource(R.string.cast_idle)
            },
            badgeActive = isCasting
        )
        Text(
            stringResource(R.string.cast_media_quick_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Renderer discovery card ──────────────────────────────────────
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.cast_available_tvs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.5.sp
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isBusy) { onScanClick() }
                            .background(
                                if (!isBusy) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh, null,
                                tint = if (!isBusy) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isDiscovering) stringResource(R.string.cast_scanning)
                            else stringResource(R.string.cast_scan),
                            style = MaterialTheme.typography.labelSmall,
                                color = if (!isBusy) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (renderers.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isDiscovering) stringResource(R.string.cast_searching_renderers)
                            else stringResource(R.string.cast_no_renderers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    // Renderer list
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        renderers.forEach { renderer ->
                            RendererItem(
                                renderer   = renderer,
                                isSelected = renderer.udn == selectedRendererUdn,
                                enabled    = !isBusy,
                                onClick    = { onRendererSelect(renderer.udn, renderer.name) }
                            )
                        }
                    }
                }

                // Error banner
                if (errorMsg != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // ── URL input card (URL-only DLNA mode) ──────────────────────────
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.cast_media_url),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp
                )
                OutlinedTextField(
                    value = selectedMediaUrl,
                    onValueChange = onMediaUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    placeholder = {
                        Text(stringResource(R.string.cast_media_url_placeholder))
                    },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.SyncAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                Text(
                    stringResource(R.string.cast_media_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Local file card ─────────────────────────────────────────────
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.cast_media_file),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp
                )

                if (hasSelectedFile) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.VideoFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            selectedFileName.ifBlank { stringResource(R.string.cast_pick_file) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable(enabled = !isBusy) { onPickFile() }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cast_pick_file),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Active cast bar / Cast action button ─────────────────────────
        if (castState is CastState.Casting) {
            ActiveCastBar(
                title        = castState.title,
                rendererName = castState.rendererName,
                playbackInfo = playbackInfo,
                onStop       = onStop,
                onPlay       = onPlay,
                onPause      = onPause,
                onSeekBack   = onSeekBack,
                onSeekForward = onSeekForward,
                onSeekTo     = onSeekTo,
                onVolumeDown = onVolumeDown,
                onVolumeUp   = onVolumeUp,
                onSetVolume  = onSetVolume,
                onMuteToggle = onMuteToggle
            )
        } else {
            val hasHttpUrl = selectedMediaUrl.startsWith("http://") || selectedMediaUrl.startsWith("https://")
            val hasSource = hasHttpUrl || hasSelectedFile
            val canCast  = hasSource && selectedRendererUdn != null && !isDiscovering && !isBusy
            val buttonLabel = when {
                isSendingUri            -> stringResource(R.string.cast_preparing)
                isStarting              -> stringResource(R.string.cast_starting)
                selectedRendererUdn == null -> stringResource(R.string.cast_select_tv_hint)
                !hasSource              -> stringResource(R.string.cast_pick_file_hint)
                else -> stringResource(R.string.cast_cast_to)
            }
            GradientActionButton(
                text    = buttonLabel,
                icon    = Icons.Filled.Cast,
                enabled = canCast,
                onClick = onCast
            )
        }
    }
}

// ─── Renderer Item ────────────────────────────────────────────────────────────

@Composable
private fun RendererItem(
    renderer: DlnaRenderer,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else surface_container_lowest
            )
            .border(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else GlassBtnBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                renderer.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (!renderer.model.isNullOrBlank()) {
                Text(
                    renderer.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Active Cast Bar ──────────────────────────────────────────────────────────

@Composable
private fun ActiveCastBar(
    title: String,
    rendererName: String,
    playbackInfo: CastPlaybackInfo,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onMuteToggle: () -> Unit
) {
    val durationMs = playbackInfo.durationMs.coerceAtLeast(0L)
    var isSeeking by remember(rendererName, title) { mutableStateOf(false) }
    var seekSlider by remember(rendererName, title) { mutableFloatStateOf(0f) }
    var isVolumeDragging by remember(rendererName, title) { mutableStateOf(false) }
    var volumeSlider by remember(rendererName, title) {
        mutableFloatStateOf((playbackInfo.volume ?: 20).toFloat())
    }

    LaunchedEffect(playbackInfo.positionMs, durationMs, isSeeking) {
        if (!isSeeking) {
            seekSlider = playbackInfo.positionMs
                .coerceIn(0L, durationMs.coerceAtLeast(0L))
                .toFloat()
        }
    }

    LaunchedEffect(playbackInfo.volume, isVolumeDragging) {
        if (!isVolumeDragging && playbackInfo.volume != null) {
            volumeSlider = playbackInfo.volume.toFloat()
        }
    }

    val alpha by rememberInfiniteTransition(label = "cast_pulse")
        .animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "cast_alpha"
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Casting to $rendererName",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${formatCastTime(playbackInfo.positionMs)} / ${formatCastTime(playbackInfo.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = seekSlider,
                onValueChange = {
                    isSeeking = true
                    seekSlider = it
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeekTo(seekSlider.toLong())
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                enabled = durationMs > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CastMiniButton(icon = Icons.Filled.FastRewind, onClick = onSeekBack)
                CastMiniButton(icon = Icons.Filled.PlayArrow, onClick = onPlay)
                CastMiniButton(icon = Icons.Filled.Pause, onClick = onPause)
                CastMiniButton(icon = Icons.Filled.FastForward, onClick = onSeekForward)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CastMiniButton(icon = Icons.Filled.VolumeDown, onClick = onVolumeDown)
                CastMiniButton(icon = Icons.Filled.VolumeUp, onClick = onVolumeUp)
                CastMiniButton(icon = Icons.Filled.VolumeOff, onClick = onMuteToggle)
                Text(
                    "${volumeSlider.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = volumeSlider,
                onValueChange = {
                    isVolumeDragging = true
                    volumeSlider = it
                },
                onValueChangeFinished = {
                    isVolumeDragging = false
                    onSetVolume(volumeSlider.roundToInt())
                },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                .clickable { onStop() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Stop, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "STOP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun CastMiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ─── Mirroring Section ────────────────────────────────────────────────────────

@Composable
private fun MirroringSection(
    isMirroring: Boolean,
    streamUrl: String?,
    authHint: String?,
    tlsFingerprint: String?,
    onStartMirroring: () -> Unit,
    onStopMirroring: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onCopySecureLink: () -> Unit
) {
    val mirrorScale by if (isMirroring) {
        rememberInfiniteTransition(label = "mirror_pulse")
            .animateFloat(
                initialValue  = 0.98f, targetValue = 1.02f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "mirror_scale"
            )
    } else {
        remember { mutableStateOf(1f) }
    }

    val signalAlpha by if (isMirroring) {
        rememberInfiniteTransition(label = "signal_alpha")
            .animateFloat(
                initialValue  = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
                label = "signal_alpha"
            )
    } else {
        remember { mutableStateOf(0.45f) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Header
        CastSectionHeader(
            icon      = Icons.Filled.Smartphone,
            title     = stringResource(R.string.cast_mirroring_title),
            badgeText = when {
                isMirroring -> stringResource(R.string.cast_streaming)
                else        -> stringResource(R.string.cast_ready)
            },
            badgeActive = isMirroring
        )
        Text(
            stringResource(R.string.cast_mirroring_realtime_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Phone ↔ TV visualization ─────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(mirrorScale)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                    else surface_container_low
                )
                .border(
                    1.dp,
                    if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    else GlassBtnBorder,
                    RoundedCornerShape(28.dp)
                )
                .padding(28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Phone ↔ TV icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp, 96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else surface_container_lowest
                            )
                            .border(2.dp,
                                if (isMirroring) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Smartphone, null,
                            tint = if (isMirroring) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outlineVariant
                        )
                    }

                    // Signal line
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(listOf(
                                    MaterialTheme.colorScheme.outlineVariant,
                                    if (isMirroring)
                                        MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha)
                                    else
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                    MaterialTheme.colorScheme.outlineVariant
                                ))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SyncAlt, null,
                            tint = if (isMirroring)
                                MaterialTheme.colorScheme.primary.copy(alpha = signalAlpha)
                            else
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier
                                .background(
                                    if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else surface_container_low
                                )
                                .padding(horizontal = 6.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(88.dp, 56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isMirroring) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else surface_container_lowest
                            )
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // Action button
                GradientActionButton(
                    text = when {
                        isMirroring -> stringResource(R.string.cast_stop_mirroring)
                        else        -> stringResource(R.string.cast_start_mirroring)
                    },
                    icon = if (isMirroring) Icons.Filled.Stop else Icons.Filled.Smartphone,
                    enabled = true,
                    isDestructive = isMirroring,
                    onClick = if (isMirroring) onStopMirroring else onStartMirroring
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.cast_low_latency_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.cast_low_latency_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }

                // Stream URL card when mirroring is active
                if (isMirroring && streamUrl != null) {
                    StreamUrlCard(
                        url = streamUrl,
                        authHint = authHint,
                        tlsFingerprint = tlsFingerprint,
                        onCopyEndpoint = onCopyUrl,
                        onCopySecureLink = onCopySecureLink
                    )
                }
            }
        }
    }
}

// ─── Stream URL Card ──────────────────────────────────────────────────────────

@Composable
private fun StreamUrlCard(
    url: String,
    authHint: String?,
    tlsFingerprint: String?,
    onCopyEndpoint: (String) -> Unit,
    onCopySecureLink: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surface_container_lowest)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.cast_mirror_stream_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                url,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = primary_fixed_dim,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { onCopyEndpoint(url) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCopy, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Key,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cast_auth_token_masked, authHint ?: "••••"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.cast_copy_auth_header),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopySecureLink() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Fingerprint,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cast_tls_fingerprint_label, tlsFingerprint ?: "unknown"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            stringResource(R.string.cast_mirror_open_hint),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

// ─── Shared Composables ───────────────────────────────────────────────────────

@Composable
private fun CastSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    badgeText: String,
    badgeActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // Status badge
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (badgeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                badgeText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(surface_container_low)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        content()
    }
}

@Composable
private fun GradientActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(
                if (!enabled) {
                    Brush.linearGradient(listOf(surface_bright, surface_container_high))
                } else if (isDestructive) {
                    Brush.linearGradient(listOf(
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    ))
                } else {
                    Brush.linearGradient(listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    ))
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, null,
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp
            )
        }
    }
}

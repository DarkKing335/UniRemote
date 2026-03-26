package com.example.uniremote.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.R
import com.example.uniremote.data.UserMacro
import com.example.uniremote.network.TvAppUiModel
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.components.remotePressable
import com.example.uniremote.ui.theme.*
import com.example.uniremote.viewmodel.ConnectionStatus
import com.example.uniremote.viewmodel.RemoteViewModel
import com.example.uniremote.ui.components.NavigationTab

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppsScreen(vm: RemoteViewModel, onNavigate: (NavigationTab) -> Unit) {
    val apps        by vm.installedApps.collectAsStateWithLifecycle()
    val isLoading   by vm.isLoadingApps.collectAsStateWithLifecycle()
    val status      by vm.connectionStatus.collectAsStateWithLifecycle()
    val userMacros  by vm.userMacros.collectAsStateWithLifecycle()
    val isConnected  = status is ConnectionStatus.Connected
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        if (status is ConnectionStatus.Error) {
            snackbarHost.showSnackbar(
                message  = (status as ConnectionStatus.Error).message,
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        topBar    = { TopBar(title = stringResource(R.string.main_remote_title), onPowerClick = { vm.power() }) },
        bottomBar = { BottomNavBar(currentTab = NavigationTab.APPS, onTabSelected = onNavigate) },
        snackbarHost  = { SnackbarHost(snackbarHost) },
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
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                QuickLaunchSection(
                    isLoading   = isLoading,
                    isConnected = isConnected,
                    apps        = apps,
                    onSync      = { vm.loadInstalledApps() },
                    onLaunch    = { appId -> vm.launchApp(appId) }
                )
                CustomMacrosSection(
                    macros = userMacros,
                    onRun  = { vm.runUserMacro(it) }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick App Launch section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun QuickLaunchSection(
    isLoading: Boolean,
    isConnected: Boolean,
    apps: List<TvAppUiModel>,
    onSync: () -> Unit,
    onLaunch: (String) -> Unit
) {
    // Spinner animation
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spinner_rot"
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = stringResource(R.string.apps_quick_launch_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.apps_installed_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Sync button / status chip
            when {
                isLoading -> {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surface_container_high)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = rotation }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.apps_syncing),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }
                apps.isNotEmpty() -> {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surface_container_high)
                            .clickable { onSync() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${apps.size} APPS",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surface_container_high)
                            .clickable { if (isConnected) onSync() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.apps_sync),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        // Content area
        when {
            !isConnected && apps.isEmpty() -> AppListNotConnected()
            isLoading                       -> AppGridSkeleton()
            apps.isEmpty()                  -> AppListEmpty(onSync)
            else                            -> AppGrid(apps, onLaunch)
        }
    }
}

// Not-connected empty state
@Composable
private fun AppListNotConnected() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(surface_container_low)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.apps_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// Skeleton loading shimmer
@Composable
private fun AppGridSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmer_alpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(4) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(surface_container_high.copy(alpha = shimmerAlpha))
                    )
                }
            }
        }
    }
}

// Empty state
@Composable
private fun AppListEmpty(onSync: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(surface_container_low)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.apps_no_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            TextButton(onClick = onSync) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.apps_sync_from_tv), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Actual app grid (non-lazy so it can live inside a ScrollColumn)
@Composable
private fun AppGrid(apps: List<TvAppUiModel>, onLaunch: (String) -> Unit) {
    // Pick a deterministic tint color based on app name hash
    fun tintFor(name: String): Color {
        val hue = ((name.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        return Color.hsv(hue, 0.55f, 0.85f)
    }

    // Pick a fitting icon by common app keywords
    fun iconFor(name: String): ImageVector = when {
        name.contains("netflix", ignoreCase = true)  -> Icons.Filled.Movie
        name.contains("youtube", ignoreCase = true)  -> Icons.Filled.PlayCircle
        name.contains("spotify", ignoreCase = true)  -> Icons.Filled.MusicNote
        name.contains("chrome",  ignoreCase = true)  -> Icons.Filled.Language
        name.contains("prime",   ignoreCase = true)  -> Icons.Filled.ShopTwo
        name.contains("disney",  ignoreCase = true)  -> Icons.Filled.Star
        name.contains("twitch",  ignoreCase = true)  -> Icons.Filled.VideogameAsset
        name.contains("plex",    ignoreCase = true)  -> Icons.Filled.VideoLibrary
        name.contains("setting", ignoreCase = true)  -> Icons.Filled.Settings
        name.contains("gallery", ignoreCase = true)  -> Icons.Filled.Image
        name.contains("file",    ignoreCase = true)  -> Icons.Filled.Folder
        name.contains("music",   ignoreCase = true)  -> Icons.Filled.MusicNote
        name.contains("tv",      ignoreCase = true)  -> Icons.Filled.Tv
        else                                          -> Icons.Filled.Apps
    }

    val rows = apps.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rowApps.forEach { app ->
                    AppButton(
                        modifier = Modifier.weight(1f),
                        icon     = iconFor(app.name),
                        color    = tintFor(app.name),
                        label    = app.name,
                        onClick  = { onLaunch(app.id) }
                    )
                }
                // Fill remaining cells in incomplete last row
                repeat(4 - rowApps.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun AppButton(modifier: Modifier, icon: ImageVector, color: Color, label: String, onClick: () -> Unit = {}) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBtnBg)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(16.dp))
            .remotePressable(shape = RoundedCornerShape(16.dp), onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x66262627)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom Macros – reads real UserMacro list from DataStore via ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CustomMacrosSection(
    macros: List<UserMacro>,
    onRun:  (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("AUTOMATION", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text("Custom Macros", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        }

        if (macros.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)).background(GlassBtnBg)
                    .border(1.dp, GlassBtnBorder, RoundedCornerShape(20.dp)).padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Bolt, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(32.dp))
                    Text("Chưa có macro nào", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.4f))
                    Text("Thêm macro từ mục Cài đặt", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.25f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                macros.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { macro ->
                            MacroCard(
                                modifier = Modifier.weight(1f),
                                title    = macro.name,
                                desc     = macro.description.ifBlank { "${macro.keys.size} phím" },
                                icon     = Icons.Filled.Bolt,   // resolved at call-site from macro.icon via iconForName
                                color    = MaterialTheme.colorScheme.primary,
                                onRun    = { onRun(macro.id) }
            )}
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun MacroCard(
    modifier: Modifier,
    title: String,
    desc: String,
    icon: ImageVector,
    color: Color,
    onRun: () -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            kotlinx.coroutines.delay(2000)
            isRunning = false
        }
    }

    Box(
        modifier = modifier
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (isRunning) color.copy(alpha = 0.15f) else GlassBtnBg)
            .border(
                1.dp,
                if (isRunning) color.copy(alpha = 0.4f) else GlassBtnBorder,
                RoundedCornerShape(24.dp)
            )
            .remotePressable(
                shape = RoundedCornerShape(24.dp), 
                raisedElevation = 8.dp, 
                pressedElevation = 2.dp,
                onClick = {
                    isRunning = !isRunning
                    if (isRunning) onRun()
                }
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(color.copy(alpha = 0.1f))
                    .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f), lineHeight = 16.sp)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, if (isRunning) color.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .background(if (isRunning) color.copy(alpha = 0.15f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isRunning) Icons.Filled.Check else Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = if (isRunning) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

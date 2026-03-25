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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uniremote.ui.components.BottomNavBar
import com.example.uniremote.ui.components.NavigationTab
import com.example.uniremote.ui.components.TopBar
import com.example.uniremote.ui.theme.*
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Data model for a TV-installed app (matches TvApp backend model)
// ─────────────────────────────────────────────────────────────────────────────
data class TvAppUi(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val tint: Color
)

// Simulated TV app list – in production this comes from the ViewModel / controller
private val simulatedTvApps = listOf(
    TvAppUi("netflix",      "Netflix",      Icons.Filled.Movie,        Color(0xFFE50914)),
    TvAppUi("youtube",      "YouTube",      Icons.Filled.PlayCircle,   Color(0xFFFF0000)),
    TvAppUi("prime",        "Prime Video",  Icons.Filled.ShopTwo,      Color(0xFF00A8E1)),
    TvAppUi("spotify",      "Spotify",      Icons.Filled.MusicNote,    Color(0xFF1DB954)),
    TvAppUi("disney",       "Disney+",      Icons.Filled.Star,         Color(0xFF113CCF)),
    TvAppUi("hbo",          "HBO Max",      Icons.Filled.Tv,           Color(0xFF9B59B6)),
    TvAppUi("twitch",       "Twitch",       Icons.Filled.VideogameAsset, Color(0xFF9146FF)),
    TvAppUi("browser",      "Browser",      Icons.Filled.Language,     Color(0xFF4285F4)),
    TvAppUi("plex",         "Plex",         Icons.Filled.VideoLibrary, Color(0xFFE5A00D)),
    TvAppUi("settings",     "TV Settings",  Icons.Filled.Settings,     Color(0xFF8D8D8D)),
    TvAppUi("gallery",      "Gallery",      Icons.Filled.Image,        Color(0xFF34A853)),
    TvAppUi("filemanager",  "Files",        Icons.Filled.Folder,       Color(0xFFFBBC05)),
)

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppsScreen(onNavigate: (NavigationTab) -> Unit) {
    // Sync state: idle | loading | loaded | error
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    var tvApps    by remember { mutableStateOf<List<TvAppUi>>(emptyList()) }

    // Simulate initial sync on first composition
    LaunchedEffect(Unit) {
        syncState = SyncState.Loading
        delay(1200)  // simulated network call
        tvApps    = simulatedTvApps
        syncState = SyncState.Loaded
    }

    Scaffold(
        topBar = {
            TopBar(title = "DIGITAL PILOT", onPowerClick = {})
        },
        bottomBar = {
            BottomNavBar(currentTab = NavigationTab.APPS, onTabSelected = onNavigate)
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
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // ── Quick Launch App Grid ──────────────────────────────────
                QuickLaunchSection(
                    syncState = syncState,
                    apps      = tvApps,
                    onSync    = {
                        // Re-sync
                        syncState = SyncState.Loading
                        tvApps    = emptyList()
                    }
                )

                // ── Custom Macros ─────────────────────────────────────────
                CustomMacrosSection()

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick App Launch section
// ─────────────────────────────────────────────────────────────────────────────
sealed class SyncState {
    object Idle    : SyncState()
    object Loading : SyncState()
    object Loaded  : SyncState()
    data class Error(val msg: String) : SyncState()
}

@Composable
fun QuickLaunchSection(
    syncState: SyncState,
    apps: List<TvAppUi>,
    onSync: () -> Unit
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
                    text = "QUICK LAUNCH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Installed Apps",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Sync button / status chip
            when (syncState) {
                SyncState.Loading -> {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surface_container_high)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Simple looping spinner using rotation animation
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
                            "SYNCING",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }
                SyncState.Loaded -> {
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
                            .clickable { onSync() }
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
                            "SYNC",
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
            syncState == SyncState.Loading -> AppGridSkeleton()
            apps.isEmpty()               -> AppListEmpty(onSync)
            else                         -> AppGrid(apps)
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
                "No apps found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            TextButton(onClick = onSync) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync from TV", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Actual app grid (non-lazy so it can live inside a ScrollColumn)
@Composable
private fun AppGrid(apps: List<TvAppUi>) {
    // Fixed 4-column grid rendered as sequential rows
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
                        icon  = app.icon,
                        color = app.tint,
                        label = app.name,
                        onClick = { /* TODO: viewModel.launchApp(app.id) */ }
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
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBtnBg)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CustomMacrosSection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(
                text = "AUTOMATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Custom Macros",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MacroCard(
                    modifier = Modifier.weight(1f),
                    title = "Movie Night",
                    desc  = "Dim lights 15%, Open Netflix, Set Audio to Theater",
                    icon  = Icons.Filled.Nightlight,
                    color = MaterialTheme.colorScheme.primary
                )
                MacroCard(
                    modifier = Modifier.weight(1f),
                    title = "Gaming Mode",
                    desc  = "Switch to HDMI 2, Enable Game Mode, Boost Bass",
                    icon  = Icons.Filled.VideogameAsset,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MacroCard(
                    modifier = Modifier.weight(1f),
                    title = "Evening Chill",
                    desc  = "Warm lights, Lo-Fi Spotify, Mute notifications",
                    icon  = Icons.Filled.FilterVintage,
                    color = primary_fixed_dim
                )
                MacroCard(
                    modifier = Modifier.weight(1f),
                    title = "Night Cycle",
                    desc  = "Shutdown all devices, Lock doors, Arm security",
                    icon  = Icons.Filled.Bedtime,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MacroCard(modifier: Modifier, title: String, desc: String, icon: ImageVector, color: Color) {
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (isRunning) { delay(2000); isRunning = false }
    }

    Box(
        modifier = modifier
            .height(200.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(if (isRunning) color.copy(alpha = 0.15f) else GlassBtnBg)
            .border(
                1.dp,
                if (isRunning) color.copy(alpha = 0.4f) else GlassBtnBorder,
                RoundedCornerShape(24.dp)
            )
            .clickable { isRunning = !isRunning }
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
                Text(text = title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
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



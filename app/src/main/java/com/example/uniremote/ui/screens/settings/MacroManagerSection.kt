package com.example.uniremote.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.uniremote.R
import com.example.uniremote.data.UserMacro
import com.example.uniremote.network.TvKey
import com.example.uniremote.ui.theme.*
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Macro Icon & Key label helpers
// ─────────────────────────────────────────────────────────────────────────────

val macroIconOptions: List<Pair<String, ImageVector>> = listOf(
    "nightlight"  to Icons.Filled.Nightlight,
    "game"        to Icons.Filled.VideogameAsset,
    "music"       to Icons.Filled.MusicNote,
    "movie"       to Icons.Filled.Movie,
    "settings"    to Icons.Filled.Settings,
    "tv"          to Icons.Filled.Tv,
    "power"       to Icons.Filled.PowerSettingsNew,
    "home"        to Icons.Filled.Home,
    "star"        to Icons.Filled.Star,
    "bedtime"     to Icons.Filled.Bedtime,
    "lightning"   to Icons.Filled.Bolt,
    "play"        to Icons.Filled.PlayArrow,
    "volume"      to Icons.AutoMirrored.Filled.VolumeUp,
    "source"      to Icons.AutoMirrored.Filled.Input,
    "search"      to Icons.Filled.Search,
)

fun iconForName(name: String): ImageVector =
    macroIconOptions.firstOrNull { it.first == name }?.second ?: Icons.Filled.PlayArrow

val tvKeyLabels: Map<TvKey, String> = mapOf(
    TvKey.HOME    to "Home",    TvKey.BACK    to "Back",    TvKey.MENU  to "Menu",
    TvKey.OK      to "OK",     TvKey.UP      to "▲",       TvKey.DOWN  to "▼",
    TvKey.LEFT    to "◀",      TvKey.RIGHT   to "▶",       TvKey.EXIT  to "Exit",
    TvKey.VOL_UP  to "Vol +",  TvKey.VOL_DOWN to "Vol -",  TvKey.MUTE  to "Mute",
    TvKey.CH_UP   to "CH +",   TvKey.CH_DOWN  to "CH -",   TvKey.POWER to "Power",
    TvKey.PLAY    to "Play",   TvKey.PAUSE   to "Pause",   TvKey.STOP  to "Stop",
    TvKey.FF      to "FF ▶▶",  TvKey.RW      to "◀◀ RW",  TvKey.NEXT  to "Next",
    TvKey.PREV    to "Prev",   TvKey.NETFLIX to "Netflix", TvKey.YOUTUBE to "YouTube",
    TvKey.SOURCE  to "Source", TvKey.HDMI_1  to "HDMI 1", TvKey.HDMI_2 to "HDMI 2",
    TvKey.HDMI_3  to "HDMI 3", TvKey.HDMI_4  to "HDMI 4", TvKey.INFO  to "Info",
    TvKey.GUIDE   to "Guide",  TvKey.SETTINGS to "Settings", TvKey.SEARCH to "Search",
    TvKey.NUM_0   to "0",      TvKey.NUM_1   to "1",       TvKey.NUM_2 to "2",
    TvKey.NUM_3   to "3",      TvKey.NUM_4   to "4",       TvKey.NUM_5 to "5",
    TvKey.NUM_6   to "6",      TvKey.NUM_7   to "7",       TvKey.NUM_8 to "8",
    TvKey.NUM_9   to "9",      TvKey.RED     to "Red",     TvKey.GREEN to "Green",
    TvKey.YELLOW  to "Yellow", TvKey.BLUE    to "Blue",    TvKey.SLEEP to "Sleep",
)

// ─────────────────────────────────────────────────────────────────────────────
// MacroManagerCard  (entry point on SettingsScreen → opens manager dialog)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MacroManagerCard(
    macros:   List<UserMacro>,
    onSave:   (UserMacro) -> Unit,
    onDelete: (String) -> Unit,
    onRun:    (String) -> Unit
) {
    var showManager by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(GlassBtnBg)
            .border(1.dp, GlassBtnBorder, RoundedCornerShape(20.dp))
            .clickable { showManager = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Macro tùy chỉnh", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(
                    if (macros.isEmpty()) "Chưa có macro nào" else "${macros.size} macro đã tạo",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (macros.isNotEmpty()) {
                Box(
                    modifier = Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("${macros.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f))
        }
    }

    if (showManager) {
        Dialog(onDismissRequest = { showManager = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1A2029), Color(0xFF090D12))))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Macro tùy chỉnh", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f)).clickable { showManager = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                        }
                    }
                    MacroManagerSection(macros = macros, onSave = onSave, onDelete = onDelete, onRun = onRun)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MacroManagerSection  (full list with add/edit/delete)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MacroManagerSection(
    macros:   List<UserMacro>,
    onSave:   (UserMacro) -> Unit,
    onDelete: (String) -> Unit,
    onRun:    (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editMacro  by remember { mutableStateOf<UserMacro?>(null) }
    val runningMacros = remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    stringResource(R.string.macro_section_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    stringResource(R.string.macro_section_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { editMacro = null; showDialog = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tạo mới", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (macros.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp)
                    .clip(RoundedCornerShape(20.dp)).background(GlassBtnBg)
                    .border(1.dp, GlassBtnBorder, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.macro_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                macros.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { macro ->
                            val isRunning = runningMacros.value.contains(macro.id)
                            UserMacroCard(
                                modifier  = Modifier.weight(1f),
                                macro     = macro,
                                isRunning = isRunning,
                                onRun     = { runningMacros.value = runningMacros.value + macro.id; onRun(macro.id) },
                                onEdit    = { editMacro = macro; showDialog = true },
                                onRunEnd  = { runningMacros.value = runningMacros.value - macro.id }
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showDialog) {
        MacroEditorDialog(
            initial   = editMacro,
            onDismiss = { showDialog = false },
            onSave    = { macro -> onSave(macro); showDialog = false },
            onDelete  = { id -> onDelete(id); showDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UserMacroCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun UserMacroCard(
    modifier:  Modifier,
    macro:     UserMacro,
    isRunning: Boolean,
    onRun:     () -> Unit,
    onEdit:    () -> Unit,
    onRunEnd:  () -> Unit
) {
    val color = MaterialTheme.colorScheme.primary

    LaunchedEffect(isRunning) {
        if (isRunning) {
            kotlinx.coroutines.delay((macro.keys.size * 250L) + 500L)
            onRunEnd()
        }
    }

    Box(
        modifier = modifier
            .height(180.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(if (isRunning) color.copy(alpha = 0.12f) else GlassBtnBg)
            .border(1.dp, if (isRunning) color.copy(alpha = 0.4f) else GlassBtnBorder, RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(color.copy(alpha = 0.12f))
                        .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Icon(iconForName(macro.icon), null, tint = color, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                        .background(surface_container_high).clickable { onEdit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Column {
                Text(macro.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                if (macro.description.isNotBlank()) {
                    Text(macro.description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("${macro.keys.size} phím", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = color.copy(alpha = 0.8f))
            }
        }
        Box(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(40.dp).clip(CircleShape)
                .background(if (isRunning) color.copy(alpha = 0.2f) else color)
                .clickable(enabled = !isRunning) { onRun() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isRunning) Icons.Filled.Check else Icons.Filled.PlayArrow,
                null,
                tint = if (isRunning) color else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MacroEditorDialog
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MacroEditorDialog(
    initial:   UserMacro?,
    onDismiss: () -> Unit,
    onSave:    (UserMacro) -> Unit,
    onDelete:  (String) -> Unit
) {
    val isEditing = initial != null
    var name             by remember { mutableStateOf(initial?.name ?: "") }
    var description      by remember { mutableStateOf(initial?.description ?: "") }
    var selectedIcon     by remember { mutableStateOf(initial?.icon ?: "play") }
    var selectedKeys     by remember { mutableStateOf(initial?.keys ?: emptyList()) }
    var showKeyPicker    by remember { mutableStateOf(false) }
    var nameError        by remember { mutableStateOf(false) }
    var keysError        by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1A2029), Color(0xFF090D12))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    if (isEditing) stringResource(R.string.macro_dialog_title_edit) else stringResource(R.string.macro_dialog_title_new),
                    style = MaterialTheme.typography.titleLarge, color = Color.White
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameError = false },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.macro_name_hint), color = Color.White.copy(alpha = 0.6f)) },
                    textStyle = LocalTextStyle.current.copy(color = Color.White), isError = nameError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = Color.White, errorBorderColor = MaterialTheme.colorScheme.error
                    )
                )
                if (nameError) Text(stringResource(R.string.macro_name_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.macro_desc_hint), color = Color.White.copy(alpha = 0.6f)) },
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f), cursorColor = Color.White
                    )
                )
                // Icon picker
                Text(stringResource(R.string.macro_icon_label), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), letterSpacing = 1.sp)
                listOf(macroIconOptions.take(8), macroIconOptions.drop(8).take(7)).forEach { chunk ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEach { (key, icon) ->
                            val isSelected = selectedIcon == key
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else surface_container_high)
                                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f), CircleShape)
                                    .clickable { selectedIcon = key },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                // Key sequence
                Text(stringResource(R.string.macro_keys_label), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), letterSpacing = 1.sp)
                if (keysError) Text(stringResource(R.string.macro_keys_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (selectedKeys.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        selectedKeys.forEachIndexed { idx, key ->
                            Row(
                                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tvKeyLabels[key] ?: key.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp).clickable { selectedKeys = selectedKeys.toMutableList().also { it.removeAt(idx) } })
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(surface_container_high)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .clickable { showKeyPicker = true; keysError = false }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.macro_add_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isEditing) {
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.macro_delete))
                        }
                    }
                    OutlinedButton(
                        onClick = onDismiss, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    ) { Text(stringResource(R.string.macro_cancel)) }
                    Button(
                        onClick = {
                            nameError = name.isBlank(); keysError = selectedKeys.isEmpty()
                            if (!nameError && !keysError) {
                                onSave(UserMacro(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(), description = description.trim(),
                                    icon = selectedIcon, keys = selectedKeys
                                ))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.macro_save)) }
                }
            }
        }
    }

    if (showKeyPicker) {
        KeyPickerDialog(
            onDismiss = { showKeyPicker = false },
            onKeySelected = { key -> selectedKeys = selectedKeys + key; showKeyPicker = false; keysError = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF1A2029),
            title = { Text("Xóa macro?", color = Color.White) },
            text  = { Text("Hành động này không thể hoàn tác.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = { TextButton(onClick = { initial?.id?.let { onDelete(it) } }) { Text("Xóa", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.macro_cancel), color = Color.White.copy(alpha = 0.6f)) } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// KeyPickerDialog
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyPickerDialog(onDismiss: () -> Unit, onKeySelected: (TvKey) -> Unit) {
    val categories = listOf(
        "Điều hướng" to listOf(TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT, TvKey.OK, TvKey.BACK, TvKey.HOME, TvKey.MENU, TvKey.EXIT),
        "Âm lượng"   to listOf(TvKey.VOL_UP, TvKey.VOL_DOWN, TvKey.MUTE),
        "Media"      to listOf(TvKey.PLAY, TvKey.PAUSE, TvKey.STOP, TvKey.FF, TvKey.RW, TvKey.NEXT, TvKey.PREV),
        "Kênh"       to listOf(TvKey.CH_UP, TvKey.CH_DOWN),
        "Nguồn"      to listOf(TvKey.SOURCE, TvKey.HDMI_1, TvKey.HDMI_2, TvKey.HDMI_3, TvKey.HDMI_4),
        "Ứng dụng"   to listOf(TvKey.NETFLIX, TvKey.YOUTUBE, TvKey.SEARCH),
        "Đặc biệt"   to listOf(TvKey.POWER, TvKey.SLEEP, TvKey.INFO, TvKey.GUIDE, TvKey.SETTINGS, TvKey.RED, TvKey.GREEN, TvKey.YELLOW, TvKey.BLUE),
        "Số"         to listOf(TvKey.NUM_0, TvKey.NUM_1, TvKey.NUM_2, TvKey.NUM_3, TvKey.NUM_4, TvKey.NUM_5, TvKey.NUM_6, TvKey.NUM_7, TvKey.NUM_8, TvKey.NUM_9),
    )
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1A2029), Color(0xFF090D12))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Chọn phím", style = MaterialTheme.typography.titleLarge, color = Color.White)
                categories.forEach { (catLabel, keys) ->
                    Text(catLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(surface_container_high)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                    .clickable { onKeySelected(key) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(tvKeyLabels[key] ?: key.name, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    }
                }
            }
        }
    }
}

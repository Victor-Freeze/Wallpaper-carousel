package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.db.WallpaperConfigEntity

// Premium aesthetic light/dark Material Color design
val StatusAmber = Color(0xFFFFAB00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWallpaperScreen(
    viewModel: WallpaperViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by viewModel.configState.collectAsStateWithLifecycle()

    var showPreviewDialog by remember { mutableStateOf(false) }
    var isManualProcessing by remember { mutableStateOf(false) }

    // Result callback for SAF Folder Picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
                
                val folderName = getDocumentDirName(context, treeUri)
                viewModel.updateLocalFolder(treeUri.toString(), folderName)
            } catch (e: Exception) {
                android.util.Log.e("MainScreen", "Failed to grant persistable permissions", e)
                viewModel.updateLocalFolder(treeUri.toString(), "Folder: " + (treeUri.lastPathSegment ?: "Device"))
            }
        }
    }

    // Permission check launcher for Notification
    var hasNotifyPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifyPermission = granted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifyPermission) {
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val activeConfig = config ?: WallpaperConfigEntity()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Elegant Header with Centered START / STOP Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = "Wallpaper carousel",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val isServiceActive = activeConfig.isActive
                Button(
                    onClick = {
                        val nextActive = !isServiceActive
                        if (nextActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifyPermission) {
                            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.toggleActive(nextActive)
                        }
                    },
                    modifier = Modifier
                        .width(180.dp)
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isServiceActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isServiceActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isServiceActive) "Stop" else "Start",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isServiceActive) "STOP" else "START",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Source Selection (100% offline local folder)
                item {
                    LocalFolderSourceCard(
                        config = activeConfig,
                        onPickLocalFolder = { folderPickerLauncher.launch(null) }
                    )
                }

                // Section 2: Destination Screens (Checkboxes in compact size)
                item {
                    DestinationSelectorCard(
                        config = activeConfig,
                        onTargetSelected = { viewModel.updateChangeTarget(it) }
                    )
                }

                // Section 3: Trigger settings with HH:mm parser instead of sliders
                item {
                    TriggerSettingsCard(
                        config = activeConfig,
                        onTriggerTypeSelected = { viewModel.updateTriggerType(it) },
                        onIntervalChanged = { viewModel.updateIntervalMinutes(it) },
                        onPauseChanged = { viewModel.updatePauseMinutes(it) }
                    )
                }

                // Section 4: Display/Cropping Formats (Visual single row schema layout)
                item {
                    ScaleModeSelectorCard(
                        config = activeConfig,
                        onScaleSelected = { viewModel.updateScaleMode(it) }
                    )
                }

                // Section 5: Beautiful Compact Preview Action Trigger
                item {
                    PreviewTriggerCard(
                        isProcessing = isManualProcessing,
                        onTriggerPreview = { showPreviewDialog = true }
                    )
                }
            }
        }

        // Dialog: Aesthetic interactive preview of selected targets and styles
        if (showPreviewDialog) {
            WallpaperPreviewDialog(
                config = activeConfig,
                onDismiss = { showPreviewDialog = false },
                onConfirm = {
                    showPreviewDialog = false
                    isManualProcessing = true
                    viewModel.triggerManualChange { success ->
                        isManualProcessing = false
                    }
                    if (!activeConfig.isActive) {
                        viewModel.toggleActive(true)
                    }
                }
            )
        }
    }
}

@Composable
fun LocalFolderSourceCard(
    config: WallpaperConfigEntity,
    onPickLocalFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "IMAGE SOURCE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val folderSelected = !config.localFolderUri.isNullOrEmpty()
                    val folderName = config.localFolderName ?: "No folder selected"

                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = if (folderSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = folderName,
                        color = if (folderSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (folderSelected) "Using custom folder from your device" else "Select a folder with wallpapers on your device",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onPickLocalFolder,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        Text(
                            text = if (folderSelected) "Change Folder" else "Select Folder",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DestinationSelectorCard(
    config: WallpaperConfigEntity,
    onTargetSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "TARGET SCREENS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            val isHomeChecked = config.changeTarget == "BOTH" || config.changeTarget == "HOME_SCREEN"
            val isLockChecked = config.changeTarget == "BOTH" || config.changeTarget == "LOCK_SCREEN"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (isHomeChecked && !isLockChecked) {
                                // Skip unchecking since at least one must be checked at all times
                            } else {
                                val next = if (!isHomeChecked && isLockChecked) "BOTH" else "LOCK_SCREEN"
                                onTargetSelected(next)
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isHomeChecked,
                        onCheckedChange = { checked ->
                            if (isHomeChecked && !isLockChecked) {
                                // Skip unchecking
                            } else {
                                val next = if (checked && isLockChecked) "BOTH" else if (checked) "HOME_SCREEN" else "LOCK_SCREEN"
                                onTargetSelected(next)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Home screen", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (!isHomeChecked && isLockChecked) {
                                // Skip unchecking since at least one must be checked at all times
                            } else {
                                val next = if (isHomeChecked && !isLockChecked) "BOTH" else "HOME_SCREEN"
                                onTargetSelected(next)
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLockChecked,
                        onCheckedChange = { checked ->
                            if (!isHomeChecked && isLockChecked) {
                                // Skip unchecking
                            } else {
                                val next = if (isHomeChecked && checked) "BOTH" else if (checked) "LOCK_SCREEN" else "HOME_SCREEN"
                                onTargetSelected(next)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Lock screen", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerSettingsCard(
    config: WallpaperConfigEntity,
    onTriggerTypeSelected: (String) -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onPauseChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "ROTATION TRIGGER & INTERVAL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isTimer = config.triggerType == "TIMER"
                Button(
                    onClick = { onTriggerTypeSelected("TIMER") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTimer) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    ),
                    border = BorderStroke(1.dp, if (isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Alarm, 
                        null, 
                        tint = if (isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Timer-based", 
                        color = if (isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, 
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = { onTriggerTypeSelected("INTERACTION") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isTimer) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    ),
                    border = BorderStroke(1.dp, if (!isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.TouchApp, 
                        null, 
                        tint = if (!isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Interactions", 
                        color = if (!isTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, 
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(targetState = config.triggerType, label = "trigger_settings") { state ->
                if (state == "TIMER") {
                    Column {
                        Text(
                            "Enter rotation interval (HH:mm):",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val minutes = config.intervalMinutes
                        val initialHhMm = String.format("%02d:%02d", minutes / 60, minutes % 60)
                        var hhMmInput by remember(minutes) { mutableStateOf(initialHhMm) }
                        var isError by remember { mutableStateOf(false) }

                        OutlinedTextField(
                            value = hhMmInput,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it == ':' }
                                hhMmInput = filtered
                                
                                val parts = filtered.split(":")
                                if (parts.size == 2) {
                                    val h = parts[0].toIntOrNull()
                                    val m = parts[1].toIntOrNull()
                                    if (h != null && m != null && m < 60) {
                                        val totalVal = h * 60 + m
                                        if (totalVal >= 5 && totalVal <= 1440) {
                                            isError = false
                                            onIntervalChanged(totalVal)
                                        } else {
                                            isError = true
                                        }
                                    } else {
                                        isError = true
                                    }
                                } else {
                                    isError = true
                                }
                            },
                            placeholder = { Text("02:00") },
                            singleLine = true,
                            isError = isError,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Rotation Delay") },
                            supportingText = {
                                Text(
                                    text = if (isError) "Please specify a range of 00:05 up to 24:00" else "Rotation interval: ${minutes / 60}h ${minutes % 60}m",
                                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Info, null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Upon timer expiration, we will apply the swap when you turn off or lock the screen to prevent interruptions.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                } else {
                    Column {
                        Text(
                            "Enter minimum pause between screen locks (HH:mm):",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val pauseVal = config.pauseMinutes
                        val initialPauseStr = String.format("%02d:%02d", pauseVal / 60, pauseVal % 60)
                        var pauseInput by remember(pauseVal) { mutableStateOf(initialPauseStr) }
                        var isPauseError by remember { mutableStateOf(false) }

                        OutlinedTextField(
                            value = pauseInput,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it == ':' }
                                pauseInput = filtered
                                
                                val parts = filtered.split(":")
                                if (parts.size == 2) {
                                    val h = parts[0].toIntOrNull()
                                    val m = parts[1].toIntOrNull()
                                    if (h != null && m != null && m < 60) {
                                        val totalVal = h * 60 + m
                                        if (totalVal >= 1 && totalVal <= 60) {
                                            isPauseError = false
                                            onPauseChanged(totalVal)
                                        } else {
                                            isPauseError = true
                                        }
                                    } else {
                                        isPauseError = true
                                    }
                                } else {
                                    isPauseError = true
                                }
                            },
                            placeholder = { Text("00:05") },
                            singleLine = true,
                            isError = isPauseError,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Minimum Pause") },
                            supportingText = {
                                Text(
                                    text = if (isPauseError) "Please specify a range of 00:01 up to 01:00" else "Active pause: $pauseVal minutes",
                                    color = if (isPauseError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Rotation completes only when lock-screen is triggered and the specified delay ($pauseVal min) has been reached.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScaleModeSelectorCard(
    config: WallpaperConfigEntity,
    onScaleSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "PHOTO CROPPING & STYLE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            val scaleOptions = listOf(
                Triple("FILL", "Fill", "Aspect Cover"),
                Triple("CENTER", "Center", "Central Clip"),
                Triple("FIT", "Fit", "Aspect Contain")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                scaleOptions.forEach { (modeId, label, tagline) ->
                    val isSelected = config.scaleMode == modeId || 
                                     (modeId == "FILL" && config.scaleMode == "SCALE_TO_FIT") ||
                                     (modeId == "FIT" && config.scaleMode == "TOUCH_BORDER")
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onScaleSelected(modeId) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background
                        ),
                        border = BorderStroke(
                            1.dp, 
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 70.dp)
                                    .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                when (modeId) {
                                    "FILL" -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))))
                                        ) {
                                            Icon(Icons.Default.CropFree, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp).align(Alignment.Center))
                                        }
                                    }
                                    "CENTER" -> {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))
                                        ) {
                                            Icon(Icons.Default.CenterFocusStrong, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp).align(Alignment.Center))
                                        }
                                    }
                                    "FIT" -> {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.SpaceBetween,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(50.dp)
                                                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f))))
                                            ) {
                                                Icon(Icons.Default.Fullscreen, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).align(Alignment.Center))
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = tagline,
                                fontSize = 9.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 11.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreviewTriggerCard(
    isProcessing: Boolean,
    onTriggerPreview: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onTriggerPreview,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(50)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Applying changes...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Visibility, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Preview Wallpaper", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun WallpaperPreviewDialog(
    config: WallpaperConfigEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var selectedPreviewTab by remember { mutableStateOf("LOCK") } // "LOCK" or "HOME"
    
    val samplePhotoUrl = config.shuffledQueue?.split("|")?.filter { it.isNotEmpty() }?.firstOrNull()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Aesthetic Preview",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Visual representation of wallpapers on device screens",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Simulated Mobile Device Mockup Container
                Box(
                    modifier = Modifier
                        .size(width = 240.dp, height = 400.dp)
                        .border(3.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (samplePhotoUrl != null) {
                        coil.compose.AsyncImage(
                            model = samplePhotoUrl,
                            contentDescription = "Wallpaper Crop Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = when (config.scaleMode) {
                                "FILL", "SCALE_TO_FIT" -> ContentScale.Crop
                                "CENTER" -> ContentScale.None
                                else -> ContentScale.Fit // "FIT" / "TOUCH_BORDER" uses aspect contain bounds
                            }
                        )
                    } else {
                        // Beautiful gradient placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "No Preview",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No folder selected",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Select a folder with wallpapers on the home screen to see the preview here.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    // Overlay Simulated UI elements depending on active screen tab
                    if (selectedPreviewTab == "LOCK") {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "10:09",
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Sunday, May 24",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }

                            Text(
                                text = "Swipe up to unlock",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top fake status indicators
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("10:09", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Wifi, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                    Icon(Icons.Default.BatteryFull, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                }
                            }

                            // Minimal search bar widget
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Google", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.Mic, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                            }

                            // Desktop fake App launcher shortcuts
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                val mockIcons = listOf(
                                    Icons.Default.Phone to Color(0xFF4CAF50),
                                    Icons.Default.Message to Color(0xFF2196F3),
                                    Icons.Default.Explore to Color(0xFFFF9800),
                                    Icons.Default.CameraAlt to Color(0xFF9C27B0)
                                )
                                mockIcons.forEach { (icon, color) ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(color),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Selector Tabs to view Home vs Lock screen layout
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isLock = selectedPreviewTab == "LOCK"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isLock) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { selectedPreviewTab = "LOCK" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Lock Screen UI",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLock) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isLock) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { selectedPreviewTab = "HOME" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Home Screen UI",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isLock) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Apply Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun getDocumentDirName(context: Context, docTreeUri: Uri): String {
    try {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            docTreeUri,
            DocumentsContract.getTreeDocumentId(docTreeUri)
        )
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx != -1) {
                    return cursor.getString(idx) ?: "Selected folder"
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MainScreen", "Error resolving directory display name", e)
    }
    return docTreeUri.lastPathSegment ?: "Device Folder"
}

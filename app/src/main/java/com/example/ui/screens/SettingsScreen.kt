package com.example.ui.screens

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import com.example.wallpaper.WallELiveWallpaperService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isNsfw = uiState.isNsfwMode
    val activeFilters = if (isNsfw) uiState.nsfwFilters else uiState.sfwFilters

    // Interval options: 1 min to 1 day
    val intervalOptions = listOf(
        1 to "1m",
        5 to "5m",
        15 to "15m",
        30 to "30m",
        60 to "1h",
        180 to "3h",
        360 to "6h",
        720 to "12h",
        1440 to "1d"
    )

    var intervalExpanded by remember { mutableStateOf(false) }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore
            }
            viewModel.setLocalFolderUri(uri.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Live Wallpaper
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("section_live_wallpaper")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wallpaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Live Wallpaper",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Apply Live Wallpaper Button
                Button(
                    onClick = {
                        applyLiveWallpaper(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("apply_live_wallpaper_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Set Live Wallpaper")
                }

                // Automatic Schedule Status & Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Automatic Schedule",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (uiState.isScheduleEnabled) "Active" else "Paused (Favorite applied)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isScheduleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = uiState.isScheduleEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setScheduleEnabled(enabled, context)
                        },
                        modifier = Modifier.testTag("schedule_switch")
                    )
                }

                // Interval dropdown
                ExposedDropdownMenuBox(
                    expanded = intervalExpanded,
                    onExpandedChange = { intervalExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val selectedLabel = intervalOptions.firstOrNull { it.first == uiState.intervalMinutes }?.second
                        ?: "${uiState.intervalMinutes}m"
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Update Interval") },
                        leadingIcon = { Icon(Icons.Default.Timelapse, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .testTag("interval_dropdown"),
                        shape = RoundedCornerShape(14.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = intervalExpanded,
                        onDismissRequest = { intervalExpanded = false }
                    ) {
                        intervalOptions.forEach { (mins, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.setIntervalMinutes(context, mins)
                                    intervalExpanded = false
                                },
                                modifier = Modifier.testTag("interval_item_$mins")
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Image Filters (stored separately for SFW and NSFW modes)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("section_filters")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Image Filters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = if (isNsfw) "NSFW Mode" else "SFW Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 1. Grayscale Filter Slider
                val grayscalePercent = (activeFilters.grayscale * 100).roundToInt()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Grayscale",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$grayscalePercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = activeFilters.grayscale,
                        onValueChange = { newVal ->
                            val snapped = (Math.round(newVal * 20f) / 20f).coerceIn(0f, 1f)
                            if (snapped != activeFilters.grayscale) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.updateFilters(
                                    grayscale = snapped,
                                    blur = activeFilters.blur,
                                    brightness = activeFilters.brightness,
                                    vignette = activeFilters.vignette
                                )
                            }
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grayscale_slider")
                    )
                }

                // 2. Blur Filter Slider
                val blurPercent = (activeFilters.blur * 100).roundToInt()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Blur",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$blurPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = activeFilters.blur,
                        onValueChange = { newVal ->
                            val snapped = (Math.round(newVal * 100f) / 100f).coerceIn(0f, 0.1f)
                            if (snapped != activeFilters.blur) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.updateFilters(
                                    grayscale = activeFilters.grayscale,
                                    blur = snapped,
                                    brightness = activeFilters.brightness,
                                    vignette = activeFilters.vignette
                                )
                            }
                        },
                        valueRange = 0f..0.1f,
                        steps = 9,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blur_slider")
                    )
                }

                // 3. Brightness Filter Slider
                val brightnessPercent = (activeFilters.brightness * 100).roundToInt()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Brightness",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$brightnessPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = activeFilters.brightness,
                        onValueChange = { newVal ->
                            val snapped = (Math.round(newVal * 20f) / 20f).coerceIn(0f, 1f)
                            if (snapped != activeFilters.brightness) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.updateFilters(
                                    grayscale = activeFilters.grayscale,
                                    blur = activeFilters.blur,
                                    brightness = snapped,
                                    vignette = activeFilters.vignette
                                )
                            }
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("brightness_slider")
                    )
                }

                // 4. Vignette Filter Slider
                val vignettePercent = (activeFilters.vignette * 100).roundToInt()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vignette",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$vignettePercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = activeFilters.vignette,
                        onValueChange = { newVal ->
                            val snapped = (Math.round(newVal * 20f) / 20f).coerceIn(0f, 1f)
                            if (snapped != activeFilters.vignette) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.updateFilters(
                                    grayscale = activeFilters.grayscale,
                                    blur = activeFilters.blur,
                                    brightness = activeFilters.brightness,
                                    vignette = snapped
                                )
                            }
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vignette_slider")
                    )
                }

                // Reset to Defaults Button
                OutlinedButton(
                    onClick = {
                        viewModel.resetFilters()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("reset_filters_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Filters")
                }
            }
        }

        // Section 3: Storage & Integration
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("section_storage_integration")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Storage & Integration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Local User Folder Picker (Moved above API Key)
                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("select_folder_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (uiState.localFolderUri.isNotBlank()) "Save Folder Selected" else "Select Save Folder"
                    )
                }

                // Wallhaven API Key input
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = { viewModel.setApiKey(it) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    label = { Text("Wallhaven API Key") },
                    placeholder = { Text("Optional for NSFW/Sketchy") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                // Direct link to Wallhaven Account Settings
                androidx.compose.material3.TextButton(
                    onClick = {
                        try {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://wallhaven.cc/settings/account")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(browserIntent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("wallhaven_account_link")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Get API Key (wallhaven.cc)",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

private fun applyLiveWallpaper(context: Context) {
    try {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, WallELiveWallpaperService::class.java)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (ex: Exception) {
            // Live wallpaper chooser not supported
        }
    }
}

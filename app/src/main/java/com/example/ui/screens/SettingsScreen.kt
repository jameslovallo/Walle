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
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import com.example.wallpaper.WallELiveWallpaperService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Apply Live Wallpaper Button
        Button(
            onClick = {
                applyLiveWallpaper(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("apply_live_wallpaper_button"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Wallpaper, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Set Live Wallpaper")
        }

        // Interval dropdown
        ExposedDropdownMenuBox(
            expanded = intervalExpanded,
            onExpandedChange = { intervalExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            val selectedLabel = intervalOptions.firstOrNull { it.first == uiState.intervalMinutes }?.second ?: "${uiState.intervalMinutes}m"
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                leadingIcon = { Icon(Icons.Default.Timelapse, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .testTag("interval_dropdown"),
                shape = RoundedCornerShape(16.dp)
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

        // Mode-specific Filters
        // Brightness slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BrightnessMedium, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Slider(
                value = activeFilters.brightness,
                onValueChange = {
                    viewModel.updateFilters(
                        brightness = it,
                        blur = activeFilters.blur,
                        vignette = activeFilters.vignette,
                        contrast = activeFilters.contrast
                    )
                },
                valueRange = 0.2f..1.8f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("brightness_slider")
            )
        }

        // Blur slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BlurOn, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Slider(
                value = activeFilters.blur,
                onValueChange = {
                    viewModel.updateFilters(
                        brightness = activeFilters.brightness,
                        blur = it,
                        vignette = activeFilters.vignette,
                        contrast = activeFilters.contrast
                    )
                },
                valueRange = 0f..25f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("blur_slider")
            )
        }

        // Vignette slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Vignette, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Slider(
                value = activeFilters.vignette,
                onValueChange = {
                    viewModel.updateFilters(
                        brightness = activeFilters.brightness,
                        blur = activeFilters.blur,
                        vignette = it,
                        contrast = activeFilters.contrast
                    )
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("vignette_slider")
            )
        }

        // Contrast slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Contrast, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Slider(
                value = activeFilters.contrast,
                onValueChange = {
                    viewModel.updateFilters(
                        brightness = activeFilters.brightness,
                        blur = activeFilters.blur,
                        vignette = activeFilters.vignette,
                        contrast = it
                    )
                },
                valueRange = 0.5f..1.5f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("contrast_slider")
            )
        }

        // Wallhaven API Key input
        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = { viewModel.setApiKey(it) },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            placeholder = { Text("Wallhaven API Key") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_input"),
            shape = RoundedCornerShape(16.dp)
        )

        // Local User Folder Picker
        OutlinedButton(
            onClick = { folderPickerLauncher.launch(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("select_folder_button"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                if (uiState.localFolderUri.isNotBlank()) "Folder Selected" else "Select Save Folder"
            )
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

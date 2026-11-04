package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    modifier: Modifier = Modifier
) {
    val isNsfw = uiState.isNsfwMode
    val activeTags = if (isNsfw) uiState.nsfwTags else uiState.sfwTags
    var showAddTagDialog by remember { mutableStateOf(false) }

    // Preset tag pool for quick selection
    val defaultSfwPool = remember {
        listOf("nature", "landscape", "cyberpunk", "minimalism", "space", "city", "digital art", "neon")
    }
    val defaultNsfwPool = remember {
        listOf("portrait", "cosplay", "boudoir", "bikini", "sensual", "model", "aesthetic", "glamour")
    }
    val currentPool = (if (isNsfw) defaultNsfwPool else defaultSfwPool) + activeTags.toList()
    val distinctTags = remember(currentPool) { currentPool.distinct() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Preview Card in device aspect ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16.5f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("wallpaper_preview_card")
        ) {
            val url = uiState.currentWallpaper.url
            if (url.isNotBlank()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val frameWidthPx = constraints.maxWidth.toFloat()
                    val frameHeightPx = constraints.maxHeight.toFloat()
                    val estimatedImageWidthPx = frameHeightPx * (16f / 9f)
                    val maxScrollPx = (estimatedImageWidthPx - frameWidthPx).coerceAtLeast(0f)
                    val currentScrollX = uiState.currentWallpaper.horizontalOffset * maxScrollPx

                    AsyncImage(
                        model = url,
                        contentDescription = "Current wallpaper",
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset((-currentScrollX).roundToInt(), 0)
                            }
                    )
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Hovering Bottom-Left: Crop button
            IconButton(
                onClick = { viewModel.openCropForCurrent() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .testTag("crop_button"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    imageVector = Icons.Default.Crop,
                    contentDescription = "Crop"
                )
            }

            // Hovering Bottom-Right: Heart button with dynamic state
            val heartContainerColor by animateColorAsState(
                targetValue = if (uiState.isCurrentFavorite) Color(0xFFE53935) else Color.Black.copy(alpha = 0.55f),
                label = "heart_color"
            )
            IconButton(
                onClick = { viewModel.toggleCurrentFavorite() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(heartContainerColor, CircleShape)
                    .testTag("favorite_toggle_button"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    imageVector = if (uiState.isCurrentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite"
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search criteria tags
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showAddTagDialog = true },
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("add_tag_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add tag",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            distinctTags.forEach { tag ->
                val isSelected = activeTags.contains(tag)
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.toggleTag(tag) },
                    label = { Text(tag) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("tag_chip_$tag")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // In NSFW mode: Sketchy / NSFW purity button group
        if (isNsfw) {
            val purityOptions = listOf("both" to "Sketchy & NSFW", "sketchy" to "Sketchy", "nsfw" to "NSFW")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("nsfw_purity_segmented_row")
            ) {
                purityOptions.forEachIndexed { index, (key, label) ->
                    SegmentedButton(
                        selected = uiState.nsfwPurity == key,
                        onClick = { viewModel.setNsfwPurity(key) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = purityOptions.size)
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Category button group: all, anime, people (defaults to people for nsfw)
        val categoryOptions = if (isNsfw) {
            listOf("people" to "People", "anime" to "Anime", "all" to "All")
        } else {
            listOf("all" to "All", "anime" to "Anime", "people" to "People")
        }
        val currentCategory = if (isNsfw) uiState.nsfwCategory else uiState.sfwCategory

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("category_segmented_row")
        ) {
            categoryOptions.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    selected = currentCategory == key,
                    onClick = { viewModel.setCategory(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = categoryOptions.size)
                ) {
                    Text(label)
                }
            }
        }
    }

    // Add custom tag dialog
    if (showAddTagDialog) {
        var inputTag by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showAddTagDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = inputTag,
                        onValueChange = { inputTag = it },
                        singleLine = true,
                        placeholder = { Text("tag") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_tag_input")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                if (inputTag.isNotBlank()) {
                                    viewModel.addCustomTag(inputTag)
                                }
                                showAddTagDialog = false
                            },
                            modifier = Modifier.testTag("submit_tag_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Add")
                        }
                    }
                }
            }
        }
    }
}

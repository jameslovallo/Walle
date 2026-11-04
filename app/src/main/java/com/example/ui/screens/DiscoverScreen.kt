package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import com.example.ui.components.PositionedWallpaperImage
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    modifier: Modifier = Modifier
) {
    val isNsfw = uiState.isNsfwMode
    val savedTags = if (isNsfw) uiState.savedNsfwTags else uiState.savedSfwTags
    val activeTags = if (isNsfw) uiState.activeNsfwTags else uiState.activeSfwTags
    var tagInputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Compact Main Preview Card in device aspect ratio with direct drag positioning
        val currentOffset = uiState.currentWallpaper.horizontalOffset
        var dragOffset by remember(currentOffset) { mutableFloatStateOf(currentOffset) }

        Box(
            modifier = Modifier
                .height(270.dp)
                .aspectRatio(9f / 18.5f)
                .shadow(8.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("wallpaper_preview_card")
        ) {
            val url = uiState.currentWallpaper.url
            if (url.isNotBlank()) {
                PositionedWallpaperImage(
                    url = url,
                    horizontalOffset = dragOffset,
                    fallbackUrl = uiState.currentWallpaper.thumbUrl,
                    onDrag = { newOffset ->
                        dragOffset = newOffset
                        viewModel.updateDragOffset(newOffset)
                    },
                    onDragEnd = {
                        viewModel.commitDragOffset(dragOffset)
                    },
                    modifier = Modifier.fillMaxSize()
                )
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
                        modifier = Modifier.size(36.dp)
                    )
                }
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
                    .padding(10.dp)
                    .size(42.dp)
                    .background(heartContainerColor, CircleShape)
                    .testTag("favorite_toggle_button"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    imageVector = if (uiState.isCurrentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text input above tags grid to add a new tag
        OutlinedTextField(
            value = tagInputText,
            onValueChange = { tagInputText = it },
            placeholder = { Text("Add tag") },
            singleLine = true,
            trailingIcon = {
                if (tagInputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            viewModel.addTag(tagInputText)
                            tagInputText = ""
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.testTag("add_tag_icon_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (tagInputText.isNotBlank()) {
                        viewModel.addTag(tagInputText)
                        tagInputText = ""
                        focusManager.clearFocus()
                    }
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("add_tag_text_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Grid of tags with 'x' button on each tag to remove from list
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tags_flow_grid"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            savedTags.forEach { tag ->
                val isSelected = activeTags.contains(tag)
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.toggleTag(tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove tag",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    viewModel.removeTag(tag)
                                }
                                .testTag("remove_tag_${tag}")
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("tag_chip_$tag")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // In NSFW mode: Segmented button for Both / Sketchy / NSFW
        if (isNsfw) {
            val purityOptions = listOf("both" to "Both", "sketchy" to "Sketchy", "nsfw" to "NSFW")
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
            Spacer(modifier = Modifier.height(4.dp))
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
}

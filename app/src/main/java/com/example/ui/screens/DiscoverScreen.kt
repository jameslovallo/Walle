package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.Wallpaper
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import com.example.ui.components.PositionedWallpaperImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    val isNsfw = uiState.isNsfwMode

    // Infinite scroll detection: as user scrolls near the end, load the next page
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisibleItem.index >= totalItems - 4 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !uiState.isDiscoverLoading && !uiState.isDiscoverLoadingMore && !uiState.discoverHasReachedEnd) {
            viewModel.loadNextPage()
        }
    }

    // Reset scroll to top when changing mode or starting a new search
    LaunchedEffect(isNsfw) {
        gridState.scrollToItem(0)
    }

    LaunchedEffect(uiState.searchGeneration) {
        gridState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("discover_screen_container")
    ) {
        // Main area: Grid showing all results returned by the query
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isDiscoverLoading && uiState.discoverWallpapers.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Searching WallHaven...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!uiState.isDiscoverLoading && uiState.discoverWallpapers.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Try a different search term or change your filters below.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discover_grid")
                ) {
                    items(
                        items = uiState.discoverWallpapers,
                        key = { it.id }
                    ) { wallpaper ->
                        DiscoverWallpaperCard(
                            wallpaper = wallpaper,
                            onDragOffset = { newOffset ->
                                viewModel.saveWallpaperOffset(wallpaper.id, newOffset)
                            },
                            onToggleFavorite = {
                                viewModel.toggleFavoriteWallpaper(wallpaper)
                            },
                            onFindSimilar = {
                                viewModel.searchSimilar(wallpaper.id)
                            },
                            onCardClick = {
                                viewModel.applyWallpaperAsCurrent(wallpaper)
                            },
                            onAppear = {
                                viewModel.lazyDetectOffset(wallpaper)
                            }
                        )
                    }

                    // Loading more spinner at the bottom
                    if (uiState.isDiscoverLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }

                    // End of results message below the grid
                    if (uiState.discoverHasReachedEnd && uiState.discoverWallpapers.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "You've reached the end of the results",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pinned Bottom Section - Seamless with bottom navigation
        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("discover_bottom_pinned_section")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search box
                OutlinedTextField(
                    value = uiState.discoverQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    placeholder = { Text("Search WallHaven...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (uiState.discoverQuery.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    viewModel.onSearchQueryChange("")
                                }
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.performSearch()
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discover_search_input")
                )

                // Filter Controls (SFW vs NSFW)
                if (!isNsfw) {
                    // SFW Mode: "All / Anime / People" selector
                    val categoryOptions = listOf("all" to "All", "anime" to "Anime", "people" to "People")
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sfw_category_segmented_row")
                    ) {
                        categoryOptions.forEachIndexed { index, (key, label) ->
                            SegmentedButton(
                                selected = uiState.discoverCategory == key,
                                onClick = {
                                    viewModel.setDiscoverCategory(key)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = categoryOptions.size)
                            ) {
                                Text(label)
                            }
                        }
                    }
                } else {
                    // NSFW Mode: Two side-by-side dropdowns taking 50% width each
                    // Category dropdown ("People" default) & Purity dropdown ("Both" default)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nsfw_filter_dropdowns_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Category Dropdown (50% width)
                        var categoryExpanded by remember { mutableStateOf(false) }
                        val categoryOptions = listOf("people" to "People", "anime" to "Anime", "all" to "All")
                        val currentCategoryLabel = categoryOptions.find { it.first == uiState.discoverCategory }?.second ?: "People"

                        ExposedDropdownMenuBox(
                            expanded = categoryExpanded,
                            onExpandedChange = { categoryExpanded = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nsfw_category_dropdown_box")
                        ) {
                            OutlinedTextField(
                                value = currentCategoryLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Category") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("nsfw_category_dropdown_field")
                            )
                            ExposedDropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false }
                            ) {
                                categoryOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontWeight = if (uiState.discoverCategory == key) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            viewModel.setDiscoverCategory(key)
                                            categoryExpanded = false
                                        },
                                        modifier = Modifier.testTag("category_option_$key")
                                    )
                                }
                            }
                        }

                        // Purity Dropdown (50% width)
                        var purityExpanded by remember { mutableStateOf(false) }
                        val purityOptions = listOf("both" to "Both", "sketchy" to "Sketchy", "nsfw" to "NSFW")
                        val currentPurityLabel = purityOptions.find { it.first == uiState.discoverPurity }?.second ?: "Both"

                        ExposedDropdownMenuBox(
                            expanded = purityExpanded,
                            onExpandedChange = { purityExpanded = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nsfw_purity_dropdown_box")
                        ) {
                            OutlinedTextField(
                                value = currentPurityLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Purity") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = purityExpanded) },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("nsfw_purity_dropdown_field")
                            )
                            ExposedDropdownMenu(
                                expanded = purityExpanded,
                                onDismissRequest = { purityExpanded = false }
                            ) {
                                purityOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontWeight = if (uiState.discoverPurity == key) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            viewModel.setDiscoverPurity(key)
                                            purityExpanded = false
                                        },
                                        modifier = Modifier.testTag("purity_option_$key")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverWallpaperCard(
    wallpaper: Wallpaper,
    onDragOffset: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onFindSimilar: () -> Unit,
    onCardClick: () -> Unit,
    onAppear: () -> Unit
) {
    // Lazy face/object detection when the card appears in composition
    LaunchedEffect(wallpaper.id) {
        onAppear()
    }

    var localOffset by remember(wallpaper.horizontalOffset) { mutableFloatStateOf(wallpaper.horizontalOffset) }

    val displayMetrics = LocalContext.current.resources.displayMetrics
    val deviceAspect = if (displayMetrics.heightPixels > 0) {
        displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
    } else {
        9f / 20f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(deviceAspect)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onCardClick() }
            .testTag("discover_card_${wallpaper.id}")
    ) {
        // Image with horizontal drag gesture: live preview during drag (smooth, no saves),
        // debounced persist only on drag end. Non-favorites are preview-only: never saved.
        PositionedWallpaperImage(
            url = wallpaper.thumbUrl.ifBlank { wallpaper.fullUrl },
            fallbackUrl = wallpaper.fullUrl,
            horizontalOffset = localOffset,
            onDrag = { newOffset ->
                localOffset = newOffset
            },
            onDragEnd = {
                onDragOffset(localOffset)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Bottom-Left: Find similar images in Discover
        IconButton(
            onClick = onFindSimilar,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .testTag("discover_find_similar_${wallpaper.id}"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Find Similar in Discover",
                modifier = Modifier.size(20.dp)
            )
        }

        // Bottom-Right: Favorite Button (Broken heart if favorited, heart if not)
        val isFav = wallpaper.isFavorite
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(40.dp)
                .background(
                    if (isFav) Color(0xFFE53935) else Color.Black.copy(alpha = 0.55f),
                    CircleShape
                )
                .testTag("fav_button_${wallpaper.id}"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
        ) {
            if (isFav) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_favorite_broken),
                    contentDescription = "Remove Favorite",
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Add Favorite",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

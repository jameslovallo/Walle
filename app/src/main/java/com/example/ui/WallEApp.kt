package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.local.CurrentWallpaperInfo
import com.example.ui.components.PositionedWallpaperImage
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.SettingsScreen

enum class NavigationTab(val title: String) {
    FAVORITES("Favorites"),
    DISCOVER("Discover"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallEApp(
    viewModel: WallEViewModel = viewModel(),
    initialOpenModal: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showCurrentFavoriteModal by rememberSaveable { mutableStateOf(initialOpenModal) }

    // Handle back button to return to home tab if on another tab
    if (selectedTabIndex != 0) {
        BackHandler {
            selectedTabIndex = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "WallHeaven",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    // Quick toggle SFW <-> NSFW mode
                    FilterChip(
                        selected = uiState.isNsfwMode,
                        onClick = { viewModel.toggleMode() },
                        label = {
                            Text(
                                text = if (uiState.isNsfwMode) "NSFW" else "SFW",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.isNsfwMode) Icons.Default.Warning else Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("mode_toggle_chip")
                    )

                    // Button showing current selected favorite in a modal view
                    IconButton(
                        onClick = { showCurrentFavoriteModal = true },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("current_favorite_preview_button")
                    ) {
                        val currentThumb = uiState.currentWallpaper.thumbUrl.ifBlank { uiState.currentWallpaper.url }
                        if (currentThumb.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                val clampedOffset = uiState.currentWallpaper.horizontalOffset.coerceIn(0f, 1f)
                                AsyncImage(
                                    model = currentThumb,
                                    contentDescription = "Current Favorite",
                                    contentScale = ContentScale.Crop,
                                    alignment = androidx.compose.ui.BiasAlignment((clampedOffset * 2f - 1f), 0f),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Wallpaper,
                                contentDescription = "Current Favorite"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationTab.values().forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = {
                            when (tab) {
                                NavigationTab.DISCOVER -> Icon(Icons.Default.Explore, contentDescription = tab.title)
                                NavigationTab.FAVORITES -> Icon(Icons.Default.Favorite, contentDescription = tab.title)
                                NavigationTab.SETTINGS -> Icon(Icons.Default.Settings, contentDescription = tab.title)
                            }
                        },
                        label = { Text(tab.title) },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = selectedTabIndex, label = "tab_crossfade") { tabIndex ->
                when (tabIndex) {
                    0 -> FavoritesScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onOpenNowPlaying = { showCurrentFavoriteModal = true },
                        onFindSimilar = { wallpaperId ->
                            selectedTabIndex = 1
                            viewModel.searchSimilar(wallpaperId)
                        }
                    )
                    1 -> DiscoverScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onOpenNowPlaying = { showCurrentFavoriteModal = true }
                    )
                    2 -> SettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
            }
        }
    }

    // Modal view showing current selected favorite
    if (showCurrentFavoriteModal) {
        ModalBottomSheet(
            onDismissRequest = { showCurrentFavoriteModal = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            CurrentFavoriteModalContent(
                currentWallpaper = uiState.currentWallpaper,
                isFavorite = uiState.isCurrentFavorite,
                isPaused = uiState.isSlideshowPaused,
                onDismiss = { showCurrentFavoriteModal = false },
                onUnfavorite = { viewModel.toggleCurrentFavorite() },
                onPlayPauseToggle = { viewModel.togglePlayPause() },
                onSkipPrevious = { viewModel.skipBackward() },
                onSkipNext = { viewModel.skipForward() },
                onDragOffsetChanged = { newOffset ->
                    viewModel.updateCurrentWallpaperOffset(newOffset)
                },
                onFindSimilar = { wallpaperId ->
                    showCurrentFavoriteModal = false
                    selectedTabIndex = 1
                    viewModel.searchSimilar(wallpaperId)
                }
            )
        }
    }
}

@Composable
fun CurrentFavoriteModalContent(
    currentWallpaper: CurrentWallpaperInfo,
    isFavorite: Boolean,
    isPaused: Boolean,
    onDismiss: () -> Unit,
    onUnfavorite: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onDragOffsetChanged: (Float) -> Unit,
    onFindSimilar: (String) -> Unit
) {
    val displayMetrics = LocalContext.current.resources.displayMetrics
    val deviceAspect = if (displayMetrics.heightPixels > 0) {
        displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
    } else {
        9f / 20f
    }

    var localOffset by remember(currentWallpaper.id, currentWallpaper.horizontalOffset) {
        mutableFloatStateOf(currentWallpaper.horizontalOffset)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("current_favorite_modal"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Current Wallpaper",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Home screen preview • Drag to reposition",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("modal_close_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        val identifier = currentWallpaper.id.ifBlank {
            currentWallpaper.url.ifBlank { currentWallpaper.thumbUrl }
        }

        // Preview card frame with home screen launcher overlay
        Box(
            modifier = Modifier
                .fillMaxWidth(0.70f)
                .aspectRatio(deviceAspect)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("modal_preview_card")
        ) {
            // Seamless crossfade transition between wallpapers when skipped.
            // Keyed on identity only so a positioning change repositions the
            // image without re-triggering the fade / reload.
            val wallpaperKey = remember(currentWallpaper.id, currentWallpaper.url) {
                currentWallpaper.id.ifBlank { currentWallpaper.url }
            }
            AnimatedContent(
                targetState = wallpaperKey,
                transitionSpec = {
                    fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400)
                    ) togetherWith fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400)
                    )
                },
                label = "wallpaper_fade_preview"
            ) {
                val targetWp = currentWallpaper
                val url = targetWp.url.ifBlank { targetWp.thumbUrl }
                if (url.isNotBlank()) {
                    PositionedWallpaperImage(
                        url = url,
                        fallbackUrl = targetWp.thumbUrl,
                        horizontalOffset = localOffset,
                        onDrag = { newOffset ->
                            localOffset = newOffset
                        },
                        onDragEnd = {
                            onDragOffsetChanged(localOffset)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E24)))
                }
            }
        }

        // Music player style control group below the image preview
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("player_control_group")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Button to find similar images in Discover
                IconButton(
                    onClick = {
                        if (identifier.isNotBlank()) {
                            onFindSimilar(identifier)
                        }
                    },
                    modifier = Modifier.testTag("player_find_similar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Find Similar in Discover",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 2. Skip Back button
                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.testTag("player_skip_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Skip Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 3. Play / Pause button
                IconButton(
                    onClick = onPlayPauseToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Play Slideshow" else "Pause Slideshow",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 4. Skip Forward button
                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.testTag("player_skip_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Forward",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 5. Unfavorite button (Broken heart icon when favorited, heart when not)
                IconButton(
                    onClick = onUnfavorite,
                    modifier = Modifier.testTag("player_unfavorite_button")
                ) {
                    if (isFavorite) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_favorite_broken),
                            contentDescription = "Unfavorite Wallpaper",
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite Wallpaper",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

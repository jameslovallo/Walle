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
    DISCOVER("Discover"),
    FAVORITES("Favorites"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallEApp(
    viewModel: WallEViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showCurrentFavoriteModal by rememberSaveable { mutableStateOf(false) }

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
                                AsyncImage(
                                    model = currentThumb,
                                    contentDescription = "Current Favorite",
                                    contentScale = ContentScale.Crop,
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
                    0 -> DiscoverScreen(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                    1 -> FavoritesScreen(
                        viewModel = viewModel,
                        uiState = uiState
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
                onDismiss = { showCurrentFavoriteModal = false },
                onUnfavorite = { viewModel.toggleCurrentFavorite() },
                onDragOffsetChanged = { newOffset ->
                    viewModel.updateCurrentWallpaperOffset(newOffset)
                },
                onFindSimilar = { wallpaperId ->
                    showCurrentFavoriteModal = false
                    selectedTabIndex = 0
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
    onDismiss: () -> Unit,
    onUnfavorite: () -> Unit,
    onDragOffsetChanged: (Float) -> Unit,
    onFindSimilar: (String) -> Unit
) {
    val displayMetrics = LocalContext.current.resources.displayMetrics
    val deviceAspect = if (displayMetrics.heightPixels > 0) {
        displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
    } else {
        9f / 20f
    }

    var localOffset by remember(currentWallpaper.horizontalOffset) {
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
                    text = "Selected Favorite",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Drag to reposition • Tap to find similar",
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

        // Preview card frame
        val identifier = currentWallpaper.id.ifBlank {
            currentWallpaper.url.ifBlank { currentWallpaper.thumbUrl }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(deviceAspect)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("modal_preview_card")
        ) {
            val url = currentWallpaper.url.ifBlank { currentWallpaper.thumbUrl }
            if (url.isNotBlank()) {
                PositionedWallpaperImage(
                    url = url,
                    fallbackUrl = currentWallpaper.thumbUrl,
                    horizontalOffset = localOffset,
                    onTap = {
                        if (identifier.isNotBlank()) {
                            onFindSimilar(identifier)
                        }
                    },
                    onDrag = { newOffset ->
                        localOffset = newOffset
                    },
                    onDragEnd = {
                        onDragOffsetChanged(localOffset)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top hint banner: Tap to find similar
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clickable {
                        if (identifier.isNotBlank()) {
                            onFindSimilar(identifier)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Tap to find similar",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Bottom-Right: Unfavorite Button
            IconButton(
                onClick = onUnfavorite,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(42.dp)
                    .background(
                        if (isFavorite) Color(0xFFE53935) else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
                    .testTag("modal_unfavorite_button"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unfavorite wallpaper" else "Favorite wallpaper",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Action button to find similar
        Button(
            onClick = {
                if (identifier.isNotBlank()) {
                    onFindSimilar(identifier)
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag("modal_find_similar_button")
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Find Similar in Discover")
        }
    }
}

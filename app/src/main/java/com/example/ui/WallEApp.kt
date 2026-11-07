package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

                    // Refresh button to change the current wallpaper
                    IconButton(
                        onClick = { viewModel.fetchNextWallpaper() },
                        modifier = Modifier.testTag("refresh_wallpaper_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
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
}

package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.local.FavoriteWallpaper
import com.example.ui.WallEUIState
import com.example.ui.WallEViewModel
import com.example.ui.components.PositionedWallpaperImage

@Composable
fun FavoritesScreen(
    viewModel: WallEViewModel,
    uiState: WallEUIState,
    onOpenNowPlaying: () -> Unit,
    onFindSimilar: (String) -> Unit = { viewModel.searchSimilar(it) },
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val deviceAspect = if (displayMetrics.heightPixels > 0) {
        displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
    } else {
        9f / 20f
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    android.util.Log.d("FavoritesScreen", "Persisted read/write grant for $uri")
                } catch (e: Exception) {
                    android.util.Log.e("FavoritesScreen", "takePersistableUriPermission failed for $uri", e)
                }
                viewModel.setLocalFolderUri(uri.toString())
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("favorites_screen_container"),
        contentAlignment = Alignment.Center
    ) {
        if (favorites.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(32.dp)
                    .testTag("favorites_empty_state")
            ) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = "No favorites yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Find some from Discover to add to your rotation.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            )
                        }
                        folderPickerLauncher.launch(pickerIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("empty_select_folder_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Select Save Folder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("favorites_grid")
            ) {
                items(
                    items = favorites,
                    key = { it.id }
                ) { fav ->
                    FavoriteCard(
                        favorite = fav,
                        deviceAspect = deviceAspect,
                        onDeleteClick = { viewModel.deleteFavorite(fav.id) },
                        onItemClick = {
                            viewModel.applyFavoriteAsCurrent(fav)
                            onOpenNowPlaying()
                        },
                        onFindSimilar = {
                            onFindSimilar(fav.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FavoriteCard(
    favorite: FavoriteWallpaper,
    deviceAspect: Float,
    onDeleteClick: () -> Unit,
    onItemClick: () -> Unit,
    onFindSimilar: () -> Unit
) {
    val effectiveOffset = if (favorite.horizontalOffset in 0f..1f) favorite.horizontalOffset else 0.5f
    val imageUrl = favorite.localFilePath?.ifBlank { null } ?: favorite.url

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(deviceAspect)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onItemClick() }
            .testTag("favorite_card_${favorite.id}")
    ) {
        // Whole image only (no generated thumbnails).
        PositionedWallpaperImage(
            url = imageUrl,
            fallbackUrl = favorite.url,
            horizontalOffset = effectiveOffset,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom-Left: Find similar in Discover
        IconButton(
            onClick = onFindSimilar,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .testTag("fav_find_similar_${favorite.id}"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Find Similar in Discover",
                modifier = Modifier.size(20.dp)
            )
        }

        // Bottom-Right: Remove favorite (filled heart — already a favorite)
        IconButton(
            onClick = onDeleteClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .testTag("fav_delete_button_${favorite.id}"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Remove Favorite",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

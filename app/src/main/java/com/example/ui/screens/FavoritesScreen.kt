package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.FavoriteWallpaper
import com.example.ui.WallEViewModel
import kotlin.math.roundToInt

@Composable
fun FavoritesScreen(
    viewModel: WallEViewModel,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .testTag("favorites_grid")
    ) {
        items(
            items = favorites,
            key = { it.id }
        ) { fav ->
            FavoriteCard(
                favorite = fav,
                onCropClick = { viewModel.openCropForFavorite(fav) },
                onDeleteClick = { viewModel.deleteFavorite(fav.id) },
                onItemClick = { viewModel.applyFavoriteAsCurrent(fav) }
            )
        }
    }
}

@Composable
fun FavoriteCard(
    favorite: FavoriteWallpaper,
    onCropClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onItemClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onItemClick() }
            .testTag("favorite_card_${favorite.id}")
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val frameWidthPx = constraints.maxWidth.toFloat()
            val frameHeightPx = constraints.maxHeight.toFloat()
            val estimatedImageWidthPx = frameHeightPx * (16f / 9f)
            val maxScrollPx = (estimatedImageWidthPx - frameWidthPx).coerceAtLeast(0f)
            val currentScrollX = favorite.horizontalOffset * maxScrollPx

            AsyncImage(
                model = favorite.thumbUrl.ifBlank { favorite.url },
                contentDescription = "Favorite image",
                contentScale = ContentScale.FillHeight,
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset((-currentScrollX).roundToInt(), 0)
                    }
            )
        }

        // Hovering Bottom-Left: Crop button
        IconButton(
            onClick = onCropClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .testTag("fav_crop_button_${favorite.id}"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
        ) {
            Icon(
                imageVector = Icons.Default.Crop,
                contentDescription = "Crop",
                modifier = Modifier.size(20.dp)
            )
        }

        // Hovering Bottom-Right: Delete button
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
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

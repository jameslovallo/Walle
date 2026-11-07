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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.FavoriteWallpaper
import com.example.ui.WallEViewModel
import com.example.ui.components.PositionedWallpaperImage

@Composable
fun FavoritesScreen(
    viewModel: WallEViewModel,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
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
                onDeleteClick = { viewModel.deleteFavorite(fav.id) },
                onItemClick = { viewModel.applyFavoriteAsCurrent(fav) },
                onDragOffset = { newOffset ->
                    viewModel.updateFavoriteOffset(fav.id, newOffset)
                }
            )
        }
    }
}

@Composable
fun FavoriteCard(
    favorite: FavoriteWallpaper,
    onDeleteClick: () -> Unit,
    onItemClick: () -> Unit,
    onDragOffset: (Float) -> Unit
) {
    val effectiveOffset = if (favorite.horizontalOffset in 0f..1f) favorite.horizontalOffset else 0.5f
    var localOffset by remember(effectiveOffset) { mutableFloatStateOf(effectiveOffset) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onItemClick() }
            .testTag("favorite_card_${favorite.id}")
    ) {
        // Image with horizontal drag gesture: live preview during drag (smooth, no DB writes),
        // debounced persist only on drag end.
        PositionedWallpaperImage(
            url = favorite.thumbUrl.ifBlank { favorite.url },
            fallbackUrl = favorite.url,
            horizontalOffset = localOffset,
            onDrag = { newOffset ->
                localOffset = newOffset
            },
            onDragEnd = {
                onDragOffset(localOffset)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Hovering Bottom-Right: Remove favorite (broken heart)
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
                painter = painterResource(id = R.drawable.ic_favorite_broken),
                contentDescription = "Delete",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

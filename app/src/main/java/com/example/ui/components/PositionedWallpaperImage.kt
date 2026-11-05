package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * Robust wallpaper image component that fills the aspect-ratio frame,
 * scales the uncropped image with ContentScale.Crop, and aligns it horizontally
 * according to horizontalOffset (0f = left, 0.5f = center, 1f = right).
 * Supports horizontal dragging to adjust the offset in real time.
 */
@Composable
fun PositionedWallpaperImage(
    url: String,
    horizontalOffset: Float,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isError by remember(url) { mutableStateOf(false) }
    var isLoading by remember(url) { mutableStateOf(true) }

    val activeUrl = if (isError && !fallbackUrl.isNullOrBlank()) fallbackUrl else url.ifBlank { fallbackUrl ?: "" }

    // Map horizontalOffset (0.0 .. 1.0) to horizontalBias (-1.0 .. 1.0)
    val effectiveOffset = if (horizontalOffset in 0f..1f) horizontalOffset else 0.5f
    val biasX = (effectiveOffset * 2f - 1f)
    var localOffset by remember(horizontalOffset) { mutableFloatStateOf(effectiveOffset) }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onDrag != null) {
                    Modifier.pointerInput(activeUrl) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val delta = dragAmount / 320f
                                val updated = (localOffset - delta).coerceIn(0f, 1f)
                                localOffset = updated
                                onDrag(updated)
                            },
                            onDragEnd = {
                                onDragEnd?.invoke()
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (activeUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(activeUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(biasX, 0f),
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    if (state is AsyncImagePainter.State.Error) {
                        if (!isError && !fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
                            isError = true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.5.dp
            )
        } else if (isError && activeUrl.isBlank()) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = "Failed to load",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlin.math.abs

/** Horizontal distance (dp) that must be exceeded before repositioning starts. */
private const val HorizontalDragThresholdDp = 24f

private enum class DragLock { Undecided, Horizontal, Vertical }

/**
 * Robust wallpaper image component that fills the aspect-ratio frame,
 * scales the uncropped image with ContentScale.Crop, and aligns it horizontally
 * according to horizontalOffset (0f = left, 0.5f = center, 1f = right).
 * Supports horizontal dragging to adjust the offset in real time.
 *
 * Dragging is locked to horizontal once the gesture clearly moves horizontally:
 * it must exceed [HorizontalDragThresholdDp] and exceed the vertical movement.
 * Until then (or once locked to vertical) the drag is not consumed, so a
 * vertical swipe scrolls the parent list instead of repositioning.
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
    val density = LocalDensity.current
    val thresholdPx = with(density) { HorizontalDragThresholdDp.dp.toPx() }
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
                        var totalHorizontal = 0f
                        var totalVertical = 0f
                        var lock = DragLock.Undecided
                        detectDragGestures(
                            onDragStart = {
                                totalHorizontal = 0f
                                totalVertical = 0f
                                lock = DragLock.Undecided
                            },
                            onDrag = { change, dragAmount ->
                                if (lock == DragLock.Vertical) return@detectDragGestures
                                totalHorizontal += dragAmount.x
                                totalVertical += dragAmount.y
                                if (lock == DragLock.Undecided) {
                                    val absH = abs(totalHorizontal)
                                    val absV = abs(totalVertical)
                                    lock = when {
                                        absH >= thresholdPx && absH > absV -> DragLock.Horizontal
                                        absV >= thresholdPx && absV >= absH -> DragLock.Vertical
                                        else -> return@detectDragGestures
                                    }
                                    if (lock != DragLock.Horizontal) return@detectDragGestures
                                }
                                change.consume()
                                val delta = dragAmount.x / 320f
                                val updated = (localOffset - delta).coerceIn(0f, 1f)
                                localOffset = updated
                                onDrag(updated)
                            },
                            onDragEnd = {
                                lock = DragLock.Undecided
                                onDragEnd?.invoke()
                            },
                            onDragCancel = {
                                lock = DragLock.Undecided
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

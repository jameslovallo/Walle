
package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.atan2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest

/**
 * Wallpaper image with horizontal crop positioning.
 *
 * Stateless w.r.t. offset: [horizontalOffset] is the single source of truth.
 * Dragging reports incremental offsets via [onDrag] / [onDragEnd] without
 * internal duplicate state, so the caller owns the offset and the image
 * never resets its position underneath the gesture.
 */
@Composable
fun PositionedWallpaperImage(
    url: String,
    horizontalOffset: Float,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    onTap: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: ((Float) -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isError by remember(url) { mutableStateOf(false) }
    var isLoading by remember(url) { mutableStateOf(true) }

    val activeUrl = if (isError && !fallbackUrl.isNullOrBlank()) fallbackUrl else url.ifBlank { fallbackUrl ?: "" }

    LaunchedEffect(isLoading, activeUrl) {
        if (!isLoading || activeUrl.isBlank()) {
            onLoadComplete?.invoke()
        }
    }

    val effectiveOffset = if (horizontalOffset in 0f..1f) horizontalOffset else 0.5f
    val biasX = (effectiveOffset * 2f - 1f)
    // Keep callbacks fresh without restarting the gesture detector.
    val currentOffset by rememberUpdatedState(effectiveOffset)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnTap by rememberUpdatedState(onTap)
    var boxWidthPx by remember { mutableStateOf(0f) }

    val showDrag = onDrag != null || onDragEnd != null || onDragStart != null

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { boxWidthPx = it.width.toFloat() }
            .then(
                if (showDrag || onTap != null) {
                    // Single detector handles tap + drag so they never compete.
                    Modifier.pointerInput(activeUrl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var isLockedHorizontal: Boolean? = null
                            var isDragging = false

                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (change.pressed) {
                                    val dragDelta = change.positionChange()
                                    totalDx += dragDelta.x
                                    totalDy += dragDelta.y

                                    if (isLockedHorizontal == null) {
                                        val dist = Math.hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                                        if (dist > viewConfiguration.touchSlop) {
                                            val angleDeg = Math.toDegrees(
                                                atan2(abs(totalDy).toDouble(), abs(totalDx).toDouble())
                                            )
                                            if (angleDeg <= 30.0) {
                                                isLockedHorizontal = true
                                                change.consume()
                                                isDragging = true
                                                currentOnDragStart?.invoke()
                                                // First move: apply the accumulated slop-cleared motion
                                                // in one step using incremental pixels.
                                                val width = boxWidthPx.takeIf { it > 0f } ?: 320f
                                                val delta = totalDx / width
                                                val next = (currentOffset - delta).coerceIn(0f, 1f)
                                                currentOnDrag?.invoke(next)
                                            } else {
                                                isLockedHorizontal = false
                                            }
                                        }
                                    } else if (isLockedHorizontal == true) {
                                        change.consume()
                                        val width = boxWidthPx.takeIf { it > 0f } ?: 320f
                                        val delta = dragDelta.x / width
                                        val next = (currentOffset - delta).coerceIn(0f, 1f)
                                        currentOnDrag?.invoke(next)
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (isDragging) {
                                currentOnDragEnd?.invoke(currentOffset)
                            } else if (isLockedHorizontal != true) {
                                // No horizontal lock was acquired: treat as tap.
                                currentOnTap?.invoke()
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (activeUrl.isNotBlank()) {
            // Remembered so every drag frame reuses the same request object
            // instead of rebuilding (and re-dispatching) a new ImageRequest.
            // Subsampled to grid/modal size (still the whole image — just
            // decoded at display size, not full 4K) and no crossfade so
            // large libraries don't pay fade animations per card.
            val request = remember(activeUrl) {
                ImageRequest.Builder(context)
                    .data(activeUrl)
                    .size(720, 1280)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = request,
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

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
 * Supports horizontal dragging to adjust the offset smoothly in real time,
 * saving positioning data only when the user finishes dragging.
 * Supports optional onTap callback for tapping actions.
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

    // Map localOffset (0.0 .. 1.0) to horizontalBias (-1.0 .. 1.0)
    val effectiveOffset = if (horizontalOffset in 0f..1f) horizontalOffset else 0.5f
    var localOffset by remember(horizontalOffset) { mutableFloatStateOf(effectiveOffset) }
    val biasX = (localOffset * 2f - 1f)

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onTap != null) {
                    Modifier.pointerInput(activeUrl) {
                        detectTapGestures(onTap = { onTap() })
                    }
                } else Modifier
            )
            .then(
                if (onDragEnd != null || onDrag != null) {
                    Modifier.pointerInput(activeUrl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var isLockedHorizontal: Boolean? = null

                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (change.pressed) {
                                    val dragDelta = change.positionChange()
                                    totalDx += dragDelta.x
                                    totalDy += dragDelta.y

                                    if (isLockedHorizontal == null) {
                                        val dist = Math.hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                                        val touchSlop = viewConfiguration.touchSlop
                                        if (dist > touchSlop) {
                                            // Angle from horizontal in degrees: 0 deg is pure horizontal, 90 deg is pure vertical
                                            val angleRad = atan2(abs(totalDy).toDouble(), abs(totalDx).toDouble())
                                            val angleDeg = Math.toDegrees(angleRad)
                                            // Strictly horizontal motion, with 30 degrees of tolerance away from horizontal
                                            if (angleDeg <= 30.0) {
                                                isLockedHorizontal = true
                                                change.consume()
                                                val delta = totalDx / 320f
                                                localOffset = (localOffset - delta).coerceIn(0f, 1f)
                                                onDrag?.invoke(localOffset)
                                            } else {
                                                // Vertical motion: yield to parent LazyVerticalGrid
                                                isLockedHorizontal = false
                                            }
                                        }
                                    } else if (isLockedHorizontal == true) {
                                        change.consume()
                                        val delta = dragDelta.x / 320f
                                        localOffset = (localOffset - delta).coerceIn(0f, 1f)
                                        onDrag?.invoke(localOffset)
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (isLockedHorizontal == true) {
                                onDragEnd?.invoke(localOffset)
                            }
                        }
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

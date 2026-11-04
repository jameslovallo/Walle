package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/**
 * Renders an uncropped landscape or portrait wallpaper positioned within
 * any aspect-ratio-specific frame using non-destructive horizontal positioning.
 * Allows smooth horizontal dragging without any pre-clipping or aspect distortion.
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

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url.ifBlank { fallbackUrl })
            .crossfade(true)
            .build()
    )

    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val frameWidth = constraints.maxWidth.toFloat()
        val frameHeight = constraints.maxHeight.toFloat()

        when (val state = painter.state) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp
                )
            }
            is AsyncImagePainter.State.Error -> {
                if (!fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
                    // Fallback to full url if thumbnail failed
                    val fallbackPainter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(fallbackUrl)
                            .crossfade(true)
                            .build()
                    )
                    DrawPainterCanvas(
                        painter = fallbackPainter,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        horizontalOffset = horizontalOffset,
                        urlKey = fallbackUrl,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Failed to load",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            is AsyncImagePainter.State.Success -> {
                DrawPainterCanvas(
                    painter = painter,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    horizontalOffset = horizontalOffset,
                    urlKey = url,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            }
            else -> {
                // Empty state
            }
        }
    }
}

@Composable
private fun DrawPainterCanvas(
    painter: AsyncImagePainter,
    frameWidth: Float,
    frameHeight: Float,
    horizontalOffset: Float,
    urlKey: String,
    onDrag: ((Float) -> Unit)?,
    onDragEnd: (() -> Unit)?
) {
    val intrinsicWidth = painter.intrinsicSize.width
    val intrinsicHeight = painter.intrinsicSize.height

    if (intrinsicWidth <= 0f || intrinsicHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
        return
    }

    // Scale to fill the frame completely without distorting proportions
    val scale = maxOf(frameWidth / intrinsicWidth, frameHeight / intrinsicHeight)
    val scaledWidth = intrinsicWidth * scale
    val scaledHeight = intrinsicHeight * scale

    val maxScrollX = maxOf(0f, scaledWidth - frameWidth)
    val maxScrollY = maxOf(0f, scaledHeight - frameHeight)

    // Current position
    val currentScrollX = horizontalOffset.coerceIn(0f, 1f) * maxScrollX
    val currentScrollY = maxScrollY / 2f // Center vertically

    var localOffset by remember(horizontalOffset) { mutableFloatStateOf(horizontalOffset) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onDrag != null && maxScrollX > 0f) {
                    Modifier.pointerInput(urlKey, maxScrollX) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val delta = dragAmount.x / maxScrollX
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
            )
    ) {
        clipRect {
            translate(left = -currentScrollX, top = -currentScrollY) {
                with(painter) {
                    draw(size = Size(scaledWidth, scaledHeight))
                }
            }
        }
    }
}

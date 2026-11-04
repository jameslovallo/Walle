package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.ui.WallpaperCropTarget
import kotlin.math.roundToInt

@Composable
fun CropOverlay(
    target: WallpaperCropTarget,
    onSave: (offset: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var offset by remember { mutableFloatStateOf(target.initialOffset.coerceIn(0f, 1f)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("crop_overlay_dialog")
        ) {
            // Dismiss button on top
            FilledIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .testTag("crop_dismiss_button"),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }

            // Save button on top right
            FilledIconButton(
                onClick = { onSave(offset) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .testTag("crop_save_button"),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = "Save crop")
            }

            // Center: Fixed Frame matching device aspect ratio
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(9f / 19.5f) // Matches standard modern phone aspect ratio
                        .clip(RoundedCornerShape(24.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                        .background(Color.Black)
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val frameWidthPx = constraints.maxWidth.toFloat()
                        val frameHeightPx = constraints.maxHeight.toFloat()

                        // Assume wide image where height fits and width overflows
                        // Standard landscape wallpaper ratio ~ 16:9
                        val estimatedImageWidthPx = frameHeightPx * (16f / 9f)
                        val maxScrollPx = (estimatedImageWidthPx - frameWidthPx).coerceAtLeast(0f)

                        val currentScrollX = offset * maxScrollPx

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (maxScrollPx > 0f) {
                                            val newOffset = (offset - (dragAmount.x / maxScrollPx)).coerceIn(0f, 1f)
                                            offset = newOffset
                                        }
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = target.url,
                                contentDescription = "Crop preview",
                                contentScale = ContentScale.FillHeight,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset {
                                        IntOffset((-currentScrollX).roundToInt(), 0)
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Smooth sliding controller
                Slider(
                    value = offset,
                    onValueChange = { offset = it },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .testTag("crop_horizontal_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

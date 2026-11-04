package com.example.domain.facedetection

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object FaceDetectorHelper {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .build()

    private val detector by lazy {
        FaceDetection.getClient(detectorOptions)
    }

    /**
     * Refactored: Only provides positioning data (0.0 to 1.0) for how the untouched
     * image should be positioned horizontally within the aspect-ratio-specific frame.
     * Never mutates or crops the image.
     */
    suspend fun calculateSmartCropOffset(
        bitmap: Bitmap,
        frameAspectRatio: Float = 9f / 19.5f
    ): Float = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val imgWidth = bitmap.width.toFloat()
                    val imgHeight = bitmap.height.toFloat()
                    val imgAspect = imgWidth / imgHeight

                    // If image is narrower than or equal to frame (very tall narrow portrait),
                    // no horizontal scroll is available, so center it.
                    if (imgAspect <= frameAspectRatio) {
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    // Visible width in image coordinates when scaled to fit vertical frame height
                    val visibleWidthInImage = imgHeight * frameAspectRatio
                    val scrollableWidth = imgWidth - visibleWidthInImage

                    if (scrollableWidth <= 1f) {
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    if (faces.isEmpty()) {
                        // When no face detected, center image (0.5f) so user has full range left and right
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    // Find primary face (largest area)
                    val primaryFace = faces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    } ?: faces[0]

                    val faceCenterX = primaryFace.boundingBox.centerX().toFloat()

                    // Position face toward the right side of the visible frame (e.g. at 68% of the visible window)
                    val targetFrameFraction = 0.68f
                    val targetWindowLeft = faceCenterX - (targetFrameFraction * visibleWidthInImage)
                    val calculatedOffset = (targetWindowLeft / scrollableWidth).coerceIn(0f, 1f)

                    continuation.resume(calculatedOffset)
                }
                .addOnFailureListener {
                    continuation.resume(0.5f)
                }
        } catch (e: Exception) {
            continuation.resume(0.5f)
        }
    }
}

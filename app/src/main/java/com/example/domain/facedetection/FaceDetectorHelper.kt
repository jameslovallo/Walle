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
     * Calculates the smart horizontal offset (0.0 to 1.0) so that detected faces
     * appear on the right side of a portrait screen.
     * Non-destructive: returns a float offset.
     */
    suspend fun calculateSmartCropOffset(
        bitmap: Bitmap,
        screenAspectRatio: Float = 9f / 19.5f
    ): Float = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    // Find the most prominent face (largest bounding box)
                    val primaryFace = faces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    } ?: faces[0]

                    val faceCenterXRatio = primaryFace.boundingBox.centerX().toFloat() / bitmap.width.toFloat()
                    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

                    // If bitmap is wider than screen aspect ratio
                    val visibleWidthRatio = (bitmap.height.toFloat() * screenAspectRatio) / bitmap.width.toFloat()

                    if (visibleWidthRatio in 0.01f..0.99f) {
                        // Position face at ~72% (right side of screen)
                        val targetScreenX = 0.72f
                        val desiredWindowStart = faceCenterXRatio - (targetScreenX * visibleWidthRatio)
                        val maxWindowStart = 1.0f - visibleWidthRatio
                        val computedOffset = (desiredWindowStart / maxWindowStart).coerceIn(0f, 1f)
                        continuation.resume(computedOffset)
                    } else {
                        continuation.resume(0.5f)
                    }
                }
                .addOnFailureListener {
                    continuation.resume(0.5f)
                }
        } catch (e: Exception) {
            continuation.resume(0.5f)
        }
    }
}

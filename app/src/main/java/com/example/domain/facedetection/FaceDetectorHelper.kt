package com.example.domain.facedetection

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object FaceDetectorHelper {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setMinFaceSize(0.08f)
        .build()

    private val detector by lazy {
        FaceDetection.getClient(detectorOptions)
    }

    /**
     * Determines horizontal positioning offset (0.0 to 1.0) to place the detected
     * face/subject at the right side of the device screen frame, strictly within the
     * bounds of the device's aspect ratio. Never mutates or destructive-crops the original image.
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
                    if (imgWidth <= 0f || imgHeight <= 0f) {
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    val imgAspect = imgWidth / imgHeight

                    // If image is narrower than frame (narrow portrait), no horizontal crop available
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
                        // When no face is detected, center image (0.5f) so user has full range
                        continuation.resume(0.5f)
                        return@addOnSuccessListener
                    }

                    // Find primary face (largest area)
                    val primaryFace = faces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    } ?: faces[0]

                    val faceCenterX = primaryFace.boundingBox.centerX().toFloat()

                    // Configure to position the face to set the focus to the right side of the screen
                    // (Targeting ~70% across the screen frame width, within the device aspect ratio)
                    val targetScreenFraction = 0.70f
                    val targetWindowLeft = faceCenterX - (targetScreenFraction * visibleWidthInImage)
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

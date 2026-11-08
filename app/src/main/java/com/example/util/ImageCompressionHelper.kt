package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCompressionHelper {
    private const val TAG = "ImageCompressionHelper"

    /**
     * Target resolution scale options:
     * - "original": No compression/downscaling
     * - "4k": Max dimensions 3840 x 2160 (or 2160 x 3840 for portrait)
     * - "1080p": Max dimensions 1920 x 1080 (or 1080 x 1920 for portrait)
     * - "device": Screen resolution dimensions
     */
    fun getTargetMaxDimensions(scale: String, context: Context): Pair<Int, Int>? {
        return when (scale.lowercase()) {
            "4k" -> Pair(3840, 2160)
            "1080p" -> Pair(1920, 1080)
            "device" -> {
                val metrics = context.resources.displayMetrics
                val w = metrics.widthPixels.coerceAtLeast(720)
                val h = metrics.heightPixels.coerceAtLeast(1280)
                Pair(max(w, h), minOf(w, h))
            }
            else -> null // "original" -> no downscaling
        }
    }

    /**
     * Checks if a file exceeds target dimensions and compresses it using WebP (or high-efficiency JPEG fallback).
     * Overwrites or replaces the target document file if compression is needed.
     * Returns true if optimization was performed and saved.
     */
    suspend fun optimizeDocumentFile(
        context: Context,
        docFile: DocumentFile,
        targetScale: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetScale.equals("original", ignoreCase = true)) return@withContext false

        val maxDims = getTargetMaxDimensions(targetScale, context) ?: return@withContext false
        val uri = docFile.uri

        try {
            // First decode bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@withContext false

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext false

            val maxLongSide = maxDims.first
            val maxShortSide = maxDims.second

            val isPortrait = origHeight > origWidth
            val allowedW = if (isPortrait) maxShortSide else maxLongSide
            val allowedH = if (isPortrait) maxLongSide else maxShortSide

            // Calculate sample size
            var inSampleSize = 1
            if (origWidth > allowedW || origHeight > allowedH) {
                val halfWidth = origWidth / 2
                val halfHeight = origHeight / 2
                while ((halfWidth / inSampleSize) >= allowedW && (halfHeight / inSampleSize) >= allowedH) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext false

            // Precise scaling if still larger than target bounds
            var finalBitmap = decodedBitmap
            val currentW = decodedBitmap.width
            val currentH = decodedBitmap.height

            val scaleRatio = minOf(allowedW.toFloat() / currentW.toFloat(), allowedH.toFloat() / currentH.toFloat(), 1.0f)
            if (scaleRatio < 0.98f) {
                val targetW = (currentW * scaleRatio).roundToInt().coerceAtLeast(1)
                val targetH = (currentH * scaleRatio).roundToInt().coerceAtLeast(1)
                finalBitmap = Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
                if (finalBitmap != decodedBitmap) {
                    decodedBitmap.recycle()
                }
            }

            // Compress to WebP Lossy (quality 85) for broad compatibility and maximum size reduction
            val bos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            finalBitmap.compress(format, 85, bos)
            val compressedBytes = bos.toByteArray()
            bos.close()
            finalBitmap.recycle()

            // Write back to the document file if we saved size or downscaled
            if (compressedBytes.isNotEmpty()) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(compressedBytes)
                    out.flush()
                }
                return@withContext true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing document file: ${docFile.name}", e)
            false
        }
    }
}

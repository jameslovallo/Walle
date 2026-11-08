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

    /** Maximum allowed dimensions for all images (4K). */
    val MAX_4K_DIMS: Pair<Int, Int> = Pair(3840, 2160)

    /**
     * Optimizes an image file: caps dimensions at 4K max and encodes as WebP.
     * Overwrites the target document file. Attempts to rename non-WebP files to .webp.
     * Returns true if optimization was performed and saved successfully.
     */
    suspend fun optimizeDocumentFile(
        context: Context,
        docFile: DocumentFile
    ): Boolean = withContext(Dispatchers.IO) {
        val maxDims = MAX_4K_DIMS
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

            // Write back to the document file (always encoded as WebP, capped at 4K)
            if (compressedBytes.isNotEmpty()) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(compressedBytes)
                    out.flush()
                }
                // Ensure the file has a .webp extension for clarity/correct format
                try {
                    val currentName = docFile.name
                    if (currentName != null && !currentName.lowercase().endsWith(".webp")) {
                        val base = currentName.substringBeforeLast('.')
                        if (base.isNotBlank()) {
                            docFile.renameTo("$base.webp")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Optimized ${docFile.name} but could not rename to .webp", e)
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

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
import kotlin.math.max
import kotlin.math.roundToInt

object ImageCompressionHelper {
    private const val TAG = "ImageCompressionHelper"

    /** Maximum allowed dimensions for all images (4K). */
    val MAX_4K_DIMS: Pair<Int, Int> = Pair(3840, 2160)

    /**
     * Optimizes an image file: caps dimensions at 4K max and encodes as WebP.
     *
     * Replacement semantics: the original file's bytes are replaced in place
     * when the file is already `.webp`, or a sibling `.webp` file is written
     * and the original is deleted. An in-place overwrite + rename is used as
     * fallback when a sibling cannot be created.
     *
     * @return the optimized `.webp` [DocumentFile] on success, or null on
     * failure. Callers must only mark the image as optimized on non-null.
     */
    suspend fun optimizeDocumentFile(
        context: Context,
        docFile: DocumentFile,
        parentDir: DocumentFile? = null
    ): DocumentFile? = withContext(Dispatchers.IO) {
        val maxDims = MAX_4K_DIMS
        val uri = docFile.uri

        try {
            // First decode bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@withContext null

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

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
            } ?: return@withContext null

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

            if (compressedBytes.isEmpty()) return@withContext null

            val currentName = docFile.name ?: return@withContext null
            if (currentName.lowercase().endsWith(".webp")) {
                // Already a .webp file: replace bytes in place.
                return@withContext if (writeBytes(context, uri, compressedBytes)) docFile else null
            }

            val base = currentName.substringBeforeLast('.')
            if (base.isBlank()) return@withContext null
            val targetName = "$base.webp"

            // Preferred path: write a sibling .webp and delete the original.
            val parent = parentDir ?: docFile.parentFile
            if (parent != null && parent.exists()) {
                try {
                    val webpFile = parent.findFile(targetName)
                        ?: parent.createFile("image/webp", targetName)
                    if (webpFile != null && writeBytes(context, webpFile.uri, compressedBytes)) {
                        val deleted = try {
                            docFile.delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not delete original ${docFile.name}", e)
                            false
                        }
                        if (deleted) {
                            return@withContext webpFile
                        }
                        if (webpFile.uri == uri) {
                            // Provider rewrote in place despite existing check.
                            return@withContext webpFile
                        }
                        Log.w(TAG, "Wrote $targetName but could not delete original ${docFile.name}; will retry")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sibling replacement failed for ${docFile.name}, falling back to in-place", e)
                }
            }

            // Fallback: replace bytes in place, then try to rename to .webp.
            if (!writeBytes(context, uri, compressedBytes)) return@withContext null
            try {
                docFile.renameTo(targetName)
            } catch (e: Exception) {
                Log.w(TAG, "Optimized ${docFile.name} but could not rename to .webp", e)
            }
            docFile
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing document file: ${docFile.name}", e)
            null
        }
    }

    private fun writeBytes(context: Context, targetUri: Uri, bytes: ByteArray): Boolean {
        return try {
            val out = context.contentResolver.openOutputStream(targetUri, "wt") ?: return false
            out.use {
                it.write(bytes)
                it.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing optimized bytes", e)
            false
        }
    }
}

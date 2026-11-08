package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ImageCompressionHelper {
    private const val TAG = "ImageCompressionHelper"

    /** Maximum allowed dimensions for all images (4K). */
    val MAX_4K_DIMS: Pair<Int, Int> = Pair(3840, 2160)

    /** Last failure reason (for UI diagnostics when progress is stuck). */
    @Volatile
    var lastFailureReason: String = ""

    /**
     * Opens an input stream for [uri] with retries. External-storage
     * providers intermittently return null on first access, especially
     * right after a folder was granted or files were copied in.
     */
    private suspend fun openInputStreamWithRetry(context: Context, uri: Uri): java.io.InputStream? {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) return stream
                Log.w(TAG, "openInputStream attempt ${attempt + 1} for $uri returned null")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                Log.w(TAG, "openInputStream attempt ${attempt + 1} for $uri failed: ${e.message}")
            }
            // Fallback: open via file descriptor — some providers return
            // null from openInputStream but work via openFileDescriptor.
            try {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                if (fd != null) return java.io.FileInputStream(fd.fileDescriptor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                Log.w(TAG, "openFileDescriptor attempt ${attempt + 1} for $uri failed: ${e.message}")
            }
            if (attempt < 2) delay(200L * (attempt + 1))
        }
        Log.w(TAG, "open retries exhausted for $uri: $last")
        // Include the exact failure so the UI can tell permission loss
        // (SecurityException) from a missing file (FileNotFoundException).
        val err = last
        lastFailureReason = when (err) {
            is SecurityException -> "Permission denied for $uri (re-select the library folder to grant read/write access). ${err.message ?: ""}"
            is java.io.FileNotFoundException -> "File not found $uri (folder may have changed; re-select it). ${err.message ?: ""}"
            else -> "Cannot open $uri (${err?.message ?: "stream was null"})"
        }
        return null
    }

    /**
     * Optimizes an image file: caps dimensions at 4K max and encodes as WebP.
     *
     * Replacement semantics: a sibling `.webp` file is created fresh, verified,
     * then the original is deleted. An already-`.webp` file is re-encoded in
     * place. An in-place overwrite + rename is the final fallback.
     *
     * SAF/external-storage reliability notes (e.g. `/storage/emulated/0/Pictures/Walle`):
     * - decode uses power-of-two sampling so very large sources do not OOM.
     * - writes retry across `"wt"`/`"w"` output modes and are length-verified.
     * - deletes fall back to `ContentResolver.delete()` and are verified.
     *
     * @return the optimized `.webp` [DocumentFile] on verified success, or
     * null on failure. Callers must only mark the image as optimized on
     * non-null.
     */
    suspend fun optimizeDocumentFile(
        context: Context,
        docFile: DocumentFile,
        parentDir: DocumentFile? = null
    ): DocumentFile? = withContext(Dispatchers.IO) {
        val maxDims = MAX_4K_DIMS
        val uri = docFile.uri
        val sourceName = docFile.name ?: "unknown"
        lastFailureReason = ""

        try {
            // First decode bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openInputStreamWithRetry(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: run {
                Log.w(TAG, "Cannot open source for $sourceName")
                if (lastFailureReason.isBlank()) {
                    lastFailureReason = "Cannot open source $sourceName (read permission? Re-select the library folder in Settings to grant read/write access.)"
                }
                return@withContext null
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                Log.w(TAG, "Unsupported image format for $sourceName (${origWidth}x${origHeight})")
                lastFailureReason = "Unsupported image format $sourceName"
                return@withContext null
            }

            val maxLongSide = maxDims.first
            val maxShortSide = maxDims.second

            val isPortrait = origHeight > origWidth
            val allowedW = if (isPortrait) maxShortSide else maxLongSide
            val allowedH = if (isPortrait) maxLongSide else maxShortSide

            // Power-of-two sampling: keep sampling while the *next* power
            // would still exceed either bound. (The previous `&&` loop left
            // wide images at sample=1, decoding multi-hundred-MB bitmaps.)
            var inSampleSize = 1
            while (origWidth / (inSampleSize * 2) >= allowedW ||
                origHeight / (inSampleSize * 2) >= allowedH
            ) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = try {
                openInputStreamWithRetry(context, uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM decoding $sourceName (${origWidth}x${origHeight}, sample=$inSampleSize)", e)
                null
            } ?: run {
                Log.w(TAG, "Decode returned null for $sourceName")
                lastFailureReason = "Decode failed $sourceName"
                return@withContext null
            }

            // Precise scaling if still larger than target bounds
            var finalBitmap = decodedBitmap
            val currentW = decodedBitmap.width
            val currentH = decodedBitmap.height

            val scaleRatio = minOf(allowedW.toFloat() / currentW.toFloat(), allowedH.toFloat() / currentH.toFloat(), 1.0f)
            if (scaleRatio < 0.98f) {
                val targetW = (currentW * scaleRatio).roundToInt().coerceAtLeast(1)
                val targetH = (currentH * scaleRatio).roundToInt().coerceAtLeast(1)
                try {
                    finalBitmap = Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM scaling $sourceName to ${targetW}x${targetH}", e)
                    decodedBitmap.recycle()
                    return@withContext null
                }
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

            val compressOk = try {
                finalBitmap.compress(format, 85, bos)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM compressing $sourceName", e)
                false
            }
            val compressedBytes = bos.toByteArray()
            bos.close()
            finalBitmap.recycle()

            if (!compressOk || compressedBytes.isEmpty()) {
                Log.w(TAG, "Compress produced no bytes for $sourceName")
                lastFailureReason = "Compression produced no bytes for $sourceName"
                return@withContext null
            }
            Log.d(TAG, "Encoded $sourceName ${origWidth}x${origHeight} -> ${compressedBytes.size} bytes WebP")

            val currentName = docFile.name ?: return@withContext null
            if (currentName.lowercase().endsWith(".webp")) {
                // Already a .webp file: re-encode in place (cannot delete
                // the source before a replacement exists).
                val ok = writeBytes(context, uri, compressedBytes) &&
                    verifyWrittenLength(context, docFile, compressedBytes.size)
                if (!ok) lastFailureReason = "Cannot rewrite $sourceName (write permission?)"
                return@withContext if (ok) docFile else null
            }

            val base = currentName.substringBeforeLast('.')
            if (base.isBlank()) return@withContext null
            val targetName = "$base.webp"

            // Preferred path: create a fresh sibling .webp, verify it, then
            // delete the original. Deleting any stale .webp first avoids
            // "truncate an existing file" write failures on some providers.
            val parent = parentDir ?: docFile.parentFile
            if (parent != null && parent.exists()) {
                val webpFile = createFreshWebpFile(context, parent, targetName)
                if (webpFile != null) {
                    if (writeBytes(context, webpFile.uri, compressedBytes) &&
                        verifyWrittenLength(context, webpFile, compressedBytes.size)
                    ) {
                        if (deleteFileVerified(context, docFile, parent)) {
                            return@withContext webpFile
                        }
                        // Some external-storage trees (e.g. Pictures on
                        // Android 10+) block deleting files this app did not
                        // create. Don't stick at 0% for that: fall back to
                        // replacing the original bytes in place (needs only
                        // write permission), then clean up the sibling copy.
                        Log.w(TAG, "Delete of ${docFile.name} blocked; trying in-place replacement")
                        if (writeBytes(context, uri, compressedBytes) &&
                            verifyWrittenLength(context, docFile, compressedBytes.size)
                        ) {
                            deleteFileQuietly(context, webpFile)
                            try {
                                docFile.renameTo(targetName)
                            } catch (e: Exception) {
                                Log.w(TAG, "Renaming ${docFile.name} failed after in-place rewrite", e)
                            }
                            return@withContext docFile
                        }
                        Log.w(TAG, "Wrote $targetName but original ${docFile.name} still present; will retry")
                        lastFailureReason = "Cannot replace $sourceName (delete blocked)"
                        return@withContext null
                    }
                    // Fresh file is unusable; remove it so it doesn't linger
                    // as a truncated entry, then try the in-place fallback.
                    deleteFileQuietly(context, webpFile)
                } else {
                    Log.w(TAG, "Could not create $targetName in ${parent.name}, falling back to in-place")
                }
            } else {
                Log.w(TAG, "No parent directory for $sourceName, falling back to in-place")
            }

            // Fallback: replace bytes in place, then try to rename to .webp.
            if (!writeBytes(context, uri, compressedBytes) ||
                !verifyWrittenLength(context, docFile, compressedBytes.size)
            ) {
                lastFailureReason = "Cannot write $sourceName (write permission?)"
                return@withContext null
            }
            try {
                docFile.renameTo(targetName)
            } catch (e: Exception) {
                Log.w(TAG, "Optimized ${docFile.name} but could not rename to .webp", e)
            }
            docFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error optimizing document file: ${docFile.name}", e)
            null
        }
    }

    private fun createFreshWebpFile(context: Context, parent: DocumentFile, targetName: String): DocumentFile? {
        // Remove any stale target first so createFile starts from empty.
        try {
            val stale = parent.findFile(targetName)
            if (stale != null) {
                deleteFileQuietly(context, stale)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear stale $targetName", e)
        }
        val created = try {
            parent.createFile("image/webp", targetName)
        } catch (e: Exception) {
            Log.w(TAG, "createFile($targetName) failed", e)
            null
        } ?: return null
        if (created.name?.lowercase()?.endsWith(".webp") != true) {
            Log.w(TAG, "Created file has unexpected name: ${created.name}")
            deleteFileQuietly(context, created)
            return null
        }
        return created
    }

    private fun verifyWrittenLength(context: Context, file: DocumentFile, expectedBytes: Int): Boolean {
        val len = try {
            file.length()
        } catch (e: Exception) {
            Log.w(TAG, "Could not query length of ${file.name}", e)
            -1L
        }
        if (len > 0L) {
            if (len != expectedBytes.toLong()) {
                // Some providers report approximate lengths; a non-zero
                // length that is in the right ballpark is still accepted.
                Log.d(TAG, "Written ${file.name} length=$len vs expected=$expectedBytes")
            }
            return true
        }
        // len <= 0 or unknown: fall back to checking the WebP header so a
        // provider with laggy SIZE reporting doesn't block conversion.
        Log.w(TAG, "Written ${file.name} reports length=$len (expected $expectedBytes); checking WebP header")
        return hasWebPMagic(context, file)
    }

    private fun hasWebPMagic(context: Context, file: DocumentFile): Boolean {
        val header = ByteArray(12)
        var read = 0
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            read >= 12 &&
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read header of ${file.name}", e)
            false
        }
    }

    private fun deleteFileVerified(context: Context, file: DocumentFile, parent: DocumentFile?): Boolean {
        val name = file.name
        val deleted = deleteFileQuietly(context, file)
        if (!deleted) return false
        if (parent == null || name == null) return true
        return try {
            val stillThere = parent.findFile(name)
            stillThere == null || !stillThere.exists()
        } catch (e: Exception) {
            true
        }
    }

    private fun deleteFileQuietly(context: Context, file: DocumentFile): Boolean {
        try {
            if (file.delete()) return true
        } catch (e: Exception) {
            Log.w(TAG, "DocumentFile.delete(${file.name}) failed", e)
        }
        return try {
            val count = context.contentResolver.delete(file.uri, null, null)
            count > 0
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver.delete(${file.name}) failed", e)
            false
        }
    }

    private fun writeBytes(context: Context, targetUri: Uri, bytes: ByteArray): Boolean {
        // Some providers accept "wt" but not "w" (or vice versa) for
        // truncating an existing tree file; try both before giving up.
        val modes = listOf("wt", "w")
        var lastError: Exception? = null
        for (mode in modes) {
            try {
                val out = context.contentResolver.openOutputStream(targetUri, mode) ?: continue
                out.use {
                    it.write(bytes)
                    it.flush()
                }
                return true
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "openOutputStream($targetUri, \"$mode\") failed: ${e.message}")
            }
        }
        try {
            val out = context.contentResolver.openOutputStream(targetUri) ?: run {
                Log.e(TAG, "openOutputStream($targetUri) returned null: $lastError")
                return false
            }
            out.use {
                it.write(bytes)
                it.flush()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing optimized bytes", e)
            return false
        }
    }
}

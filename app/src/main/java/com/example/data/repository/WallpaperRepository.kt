package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.api.NetworkClient
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.local.CurrentWallpaperInfo
import com.example.data.local.FavoriteWallpaper
import com.example.data.model.Wallpaper
import com.example.data.model.WallhavenImageItem
import com.example.domain.facedetection.FaceDetectorHelper
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class WallpaperRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences
) {
    private val apiService = NetworkClient.apiService
    private val favoriteDao = database.favoriteDao()
    private val imageLoader = ImageLoader(context)
    private val positioningPrefs = context.getSharedPreferences("walle_positioning_cache", Context.MODE_PRIVATE)
    private val memoryOffsetCache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val folderMutex = Mutex()
    private val optimizationMutex = Mutex()

    data class OptimizationProgress(
        val optimized: Int = 0,
        val total: Int = 0,
        val isRunning: Boolean = false,
        val failed: Int = 0,
        val lastError: String = ""
    )

    private val _optimizationProgress = MutableStateFlow(OptimizationProgress())
    val optimizationProgress: StateFlow<OptimizationProgress> = _optimizationProgress.asStateFlow()

    companion object {
        /**
         * Builds the Wallhaven `q` query from a raw search string so that
         * all relevant results are fetched.
         *
         * Every term is included as a plain tag joined with a single space.
         * No `+` (must-have) or `-` (exclude) operators are kept: those
         * narrow the result set. Terms are normalized (split on comma or
         * whitespace, operator prefixes stripped, lowercased, blank/deduped)
         * and empty input returns null so Wallhaven serves its broad listing.
         */
        fun buildWallhavenQuery(query: String?): String? {
            if (query.isNullOrBlank()) return null
            val trimmed = query.trim()
            if (trimmed.startsWith("like:", ignoreCase = true)) {
                val targetId = trimmed.substringAfter("like:").trim()
                return if (targetId.isNotBlank()) "like:$targetId" else null
            }
            return buildWallhavenQuery(trimmed.split(',', ' '))
        }

        /** Builds the Wallhaven `q` query from a list of tags. See [buildWallhavenQuery]. */
        fun buildWallhavenQuery(terms: List<String>): String? {
            val cleaned = terms
                .flatMap { it.split(',', ' ') }
                .map { it.trim().trimStart('+', '-') }
                .map { it.lowercase() }
                .filter { it.isNotBlank() && it != "all" }
                .distinct()
            return if (cleaned.isEmpty()) null else cleaned.joinToString(" ")
        }

        fun extractWallhavenId(raw: String): String {
            var id = raw.trim()
            if (id.startsWith("like:", ignoreCase = true)) {
                id = id.substringAfter("like:").trim()
            }
            if (id.contains("/")) {
                val segment = id.substringAfterLast("/")
                id = segment.removePrefix("wallhaven-").substringBefore(".")
            }
            if (id.startsWith("wallhaven-", ignoreCase = true)) {
                id = id.substringAfter("wallhaven-").trim()
            }
            return id.substringBefore(".")
        }
    }

    fun getCachedOffset(id: String): Float? {
        if (id.isBlank()) return null
        memoryOffsetCache[id]?.let { return it }
        if (positioningPrefs.contains(id)) {
            val cached = positioningPrefs.getFloat(id, -1f)
            if (cached in 0f..1f) {
                memoryOffsetCache[id] = cached
                return cached
            }
        }
        return null
    }

    fun setCachedOffset(id: String, offset: Float) {
        if (id.isBlank()) return
        val clamped = offset.coerceIn(0f, 1f)
        memoryOffsetCache[id] = clamped
        positioningPrefs.edit().putFloat(id, clamped).apply()
    }

    suspend fun getOrDeterminePositionOffset(id: String, thumbUrlOrSource: String): Float {
        if (id.isNotBlank()) {
            getCachedOffset(id)?.let { return it }
            val fav = favoriteDao.getFavoriteById(id)
            if (fav != null && fav.horizontalOffset in 0f..1f) {
                setCachedOffset(id, fav.horizontalOffset)
                return fav.horizontalOffset
            }
        }

        // Only run AI model if never determined before for this image
        val detected = detectFaceOffset(thumbUrlOrSource)
        if (id.isNotBlank()) {
            setCachedOffset(id, detected)
        }
        return detected
    }

    fun isOffsetCached(id: String): Boolean {
        if (id.isBlank()) return false
        return memoryOffsetCache.containsKey(id) || positioningPrefs.contains(id)
    }

    suspend fun lazyDetectOffset(id: String, thumbUrlOrSource: String): Float = withContext(Dispatchers.IO) {
        getCachedOffset(id)?.let { return@withContext it }
        val fav = favoriteDao.getFavoriteById(id)
        if (fav != null && fav.horizontalOffset in 0f..1f) {
            setCachedOffset(id, fav.horizontalOffset)
            return@withContext fav.horizontalOffset
        }

        val detected = detectFaceOffset(thumbUrlOrSource)
        if (id.isNotBlank()) {
            setCachedOffset(id, detected)
            if (fav != null) {
                favoriteDao.updateCropOffset(id, detected)
            }
        }
        detected
    }

    val isNsfwMode: Flow<Boolean> = preferences.isNsfwModeFlow
    val currentWallpaper: Flow<CurrentWallpaperInfo> = preferences.currentWallpaperFlow
    val sfwTags: Flow<Set<String>> = preferences.activeSfwTagsFlow
    val nsfwTags: Flow<Set<String>> = preferences.activeNsfwTagsFlow
    val savedSfwTags: Flow<Set<String>> = preferences.savedSfwTagsFlow
    val savedNsfwTags: Flow<Set<String>> = preferences.savedNsfwTagsFlow
    val activeSfwTags: Flow<Set<String>> = preferences.activeSfwTagsFlow
    val activeNsfwTags: Flow<Set<String>> = preferences.activeNsfwTagsFlow
    val nsfwPurity: Flow<String> = preferences.nsfwPurityFlow
    val sfwCategory: Flow<String> = preferences.sfwCategoryFlow
    val nsfwCategory: Flow<String> = preferences.nsfwCategoryFlow
    val apiKey: Flow<String> = preferences.apiKeyFlow
    val intervalMinutes: Flow<Int> = preferences.intervalMinutesFlow
    val sfwFilters = preferences.sfwFilterFlow
    val nsfwFilters = preferences.nsfwFilterFlow
    val localFolderUri = preferences.localFolderUriFlow

    fun getFavorites(isNsfw: Boolean): Flow<List<FavoriteWallpaper>> {
        val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
        return favoriteDao.getFavoritesByPurities(purities)
    }

    fun isFavorite(id: String): Flow<Boolean> {
        return favoriteDao.isFavorite(id)
    }

    suspend fun isFavoriteDirect(id: String): Boolean {
        return favoriteDao.isFavoriteDirect(id)
    }

    suspend fun toggleFavorite(
        id: String,
        url: String,
        thumbUrl: String,
        purity: String,
        category: String,
        cropOffset: Float,
        tags: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext false
        val exists = favoriteDao.isFavoriteDirect(id)
        if (exists) {
            // Unfavoriting acts exactly the same as deleting the favorite:
            // purge file, remove from index.json, cache, and record in deleted list
            deleteFavorite(id)
            false
        } else {
            // Un-delete if previously deleted
            preferences.removeDeletedWallpaperId(id)

            val effectiveOffset = getCachedOffset(id) ?: if (cropOffset in 0f..1f) cropOffset else 0.5f
            setCachedOffset(id, effectiveOffset)

            // Generate high-performance internal thumbnail in app internal storage
            val internalThumb = generateInternalThumbnail(id, sourceUri = null, sourceUrl = thumbUrl.ifBlank { url })
            val fav = FavoriteWallpaper(
                id = id,
                url = url,
                thumbUrl = internalThumb.ifBlank { thumbUrl },
                purity = purity,
                category = category,
                horizontalOffset = effectiveOffset,
                addedTimestamp = System.currentTimeMillis(),
                tags = tags.joinToString(",")
            )
            favoriteDao.insertFavorite(fav)
            // Save to local user folder if configured
            saveWallpaperToLocalFolder(fav)
            true
        }
    }

    suspend fun deleteFavorite(id: String) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext

        // Apply a replacement BEFORE deleting when the deleted item is live
        // on the homescreen, so the live wallpaper never points at a
        // just-deleted file/thumbnail URI.
        val currentBefore = preferences.currentWallpaperFlow.first()
        if (currentBefore.id == id) {
            try {
                val next = applyNextScheduledFavoriteWallpaper()
                if (next.isFailure) {
                    fetchAndApplyNextWallpaper()
                }
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error advancing wallpaper before deleting current", e)
            }
        }

        // Serialize folder/index mutations so a concurrent folder sync
        // cannot resurrect the deleted row from a stale snapshot.
        folderMutex.withLock {
            // 1. Remove from Room DB (this drives the Favorites grid Flow view update)
            favoriteDao.deleteById(id)
            memoryOffsetCache.remove(id)
            positioningPrefs.edit().remove(id).apply()

            // 3. Remove internal thumbnail
            try {
                val thumbFile = getInternalThumbnailFile(id)
                if (thumbFile.exists()) {
                    thumbFile.delete()
                }
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error deleting thumbnail for $id", e)
            }

            // 4. Remove physical file from user folder (sfw/, nsfw/, root) if folder is configured
            val folderUriString = preferences.localFolderUriFlow.first()
            if (folderUriString.isNotBlank()) {
                try {
                    val folderUri = Uri.parse(folderUriString)
                    val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                    if (rootDoc != null && rootDoc.exists() && rootDoc.canWrite()) {
                        val subDirs = listOfNotNull(rootDoc.findFile("sfw"), rootDoc.findFile("nsfw"), rootDoc)
                        for (dir in subDirs) {
                            if (dir.exists() && dir.isDirectory) {
                                for (f in dir.listFiles()) {
                                    val name = f.name ?: continue
                                    val base = name.substringBeforeLast('.')
                                    val cleanId = if (base.startsWith("wallhaven-", ignoreCase = true)) {
                                        base.substringAfter("wallhaven-")
                                    } else {
                                        base
                                    }
                                    if (cleanId.equals(id, ignoreCase = true)) {
                                        f.delete()
                                    }
                                }
                            }
                        }
                        // Update index.json to purge deleted item
                        updateFolderMetadataIndex(rootDoc)
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error deleting file from local folder for $id", e)
                }
            }
        }
    }

    suspend fun updateCropOffset(id: String, offset: Float) = withContext(Dispatchers.IO) {
        val clamped = offset.coerceIn(0f, 1f)
        setCachedOffset(id, clamped)
        // Only update the live wallpaper offset if this is the active wallpaper,
        // so repositioning other favorites does not disturb it.
        if (preferences.currentWallpaperFlow.first().id == id) {
            preferences.updateCurrentWallpaperOffset(clamped)
        }
        val isFav = favoriteDao.isFavoriteDirect(id)
        if (isFav) {
            favoriteDao.updateCropOffset(id, clamped)
            // Keep index.json in user folder in sync with updated positioning
            val folderUriString = preferences.localFolderUriFlow.first()
            if (folderUriString.isNotBlank()) {
                try {
                    val folderUri = Uri.parse(folderUriString)
                    val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                    if (rootDoc != null && rootDoc.exists() && rootDoc.canWrite()) {
                        updateFolderMetadataIndex(rootDoc)
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error updating index.json on offset update", e)
                }
            }
        }
    }

    /**
     * Applies a favorite as current wallpaper.
     * Uses cached positioning data if available or if set in the favorite.
     * Runs face detection ONLY if positioning has never been determined before,
     * caching the result immediately to prevent future duplicate calculations.
     */
    suspend fun applyFavorite(fav: FavoriteWallpaper) = withContext(Dispatchers.IO) {
        val finalOffset = if (fav.horizontalOffset in 0f..1f) {
            setCachedOffset(fav.id, fav.horizontalOffset)
            fav.horizontalOffset
        } else {
            val cached = getCachedOffset(fav.id)
            if (cached != null) {
                cached
            } else {
                getOrDeterminePositionOffset(
                    fav.id,
                    fav.localFilePath ?: fav.thumbUrl.ifBlank { fav.url }
                )
            }
        }
        if (fav.horizontalOffset != finalOffset) {
            favoriteDao.updateCropOffset(fav.id, finalOffset)
        }

        val favTags = if (fav.tags.isNotBlank()) {
            fav.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        preferences.setCurrentWallpaper(
            id = fav.id,
            url = fav.url,
            thumbUrl = fav.thumbUrl,
            purity = fav.purity,
            category = fav.category,
            offset = finalOffset,
            tags = favTags
        )
        notifyWallpaperChanged()
    }

    /**
     * Applies the next wallpaper strictly from the saved Favorites.
     * When scheduled, wallpapers only cycle through user favorites.
     */
    suspend fun applyNextScheduledFavoriteWallpaper(): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
            val favorites = favoriteDao.getFavoritesByPuritiesDirect(purities)
            if (favorites.isEmpty()) {
                return@withContext Result.failure(Exception("No favorites saved to cycle through"))
            }

            val current = preferences.currentWallpaperFlow.first()
            val currentIndex = favorites.indexOfFirst { it.id == current.id }
            val nextIndex = if (currentIndex != -1) {
                (currentIndex + 1) % favorites.size
            } else {
                0
            }
            val candidate = favorites[nextIndex]

            applyFavorite(candidate)

            val favTags = if (candidate.tags.isNotBlank()) {
                candidate.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }

            val wp = Wallpaper(
                id = candidate.id,
                fullUrl = candidate.url,
                thumbUrl = candidate.thumbUrl,
                purity = candidate.purity,
                category = candidate.category,
                resolution = candidate.resolution,
                horizontalOffset = candidate.horizontalOffset,
                isFavorite = true,
                tags = favTags
            )
            Result.success(wp)
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error applying next scheduled favorite", e)
            Result.failure(e)
        }
    }

    /**
     * Applies previous favorite wallpaper (skip back).
     */
    suspend fun applyPreviousScheduledFavoriteWallpaper(): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
            val favorites = favoriteDao.getFavoritesByPuritiesDirect(purities)
            if (favorites.isEmpty()) {
                return@withContext Result.failure(Exception("No favorites saved to cycle through"))
            }

            val current = preferences.currentWallpaperFlow.first()
            val currentIndex = favorites.indexOfFirst { it.id == current.id }
            val prevIndex = if (currentIndex != -1) {
                (currentIndex - 1 + favorites.size) % favorites.size
            } else {
                favorites.size - 1
            }
            val candidate = favorites[prevIndex]

            applyFavorite(candidate)

            val favTags = if (candidate.tags.isNotBlank()) {
                candidate.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }

            val wp = Wallpaper(
                id = candidate.id,
                fullUrl = candidate.url,
                thumbUrl = candidate.thumbUrl,
                purity = candidate.purity,
                category = candidate.category,
                resolution = candidate.resolution,
                horizontalOffset = candidate.horizontalOffset,
                isFavorite = true,
                tags = favTags
            )
            Result.success(wp)
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error applying previous scheduled favorite", e)
            Result.failure(e)
        }
    }

    private fun imageIdFromFileName(name: String): String? {
        val lower = name.lowercase()
        if (name.equals("index.json", ignoreCase = true)) return null
        if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))) {
            return null
        }
        val baseId = name.substringBeforeLast('.')
        return if (baseId.startsWith("wallhaven-", ignoreCase = true)) {
            baseId.substringAfter("wallhaven-")
        } else {
            baseId
        }
    }

    private fun hasWebPMagic(file: DocumentFile): Boolean {
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
            false
        }
    }

    /**
     * A file counts as optimized only if it was marked after a successful
     * write and its bytes are actually WebP. This heals previous false
     * positives where a failed attempt was marked optimized but the large
     * original file remained.
     */
    private fun isFileOptimized(file: DocumentFile, id: String, optimizedIds: Set<String>): Boolean {
        if (id !in optimizedIds) return false
        if (file.name?.lowercase()?.endsWith(".webp") == true) return true
        return hasWebPMagic(file)
    }

    /**
     * Collects every library image under [rootDoc], recursively (depth <= 3),
     * so files directly in the selected folder as well as in `sfw/`, `nsfw/`,
     * or any nested subfolder are included. Returns (file, id, parentDir).
     */
    private fun collectLibraryFiles(rootDoc: DocumentFile, maxDepth: Int = 3): List<Triple<DocumentFile, String, DocumentFile>> {
        val out = mutableListOf<Triple<DocumentFile, String, DocumentFile>>()
        fun walk(dir: DocumentFile, depth: Int) {
            val entries = try {
                dir.listFiles()
            } catch (e: Exception) {
                Log.w("WallpaperRepo", "Could not list ${dir.uri} (depth=$depth)", e)
                return
            }
            if (depth == 0) {
                Log.d("WallpaperRepo", "Library scan: ${dir.uri} canRead=${dir.canRead()} canWrite=${dir.canWrite()} entries=${entries.size}")
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) walk(entry, depth + 1)
                } else {
                    val name = entry.name ?: continue
                    if (name.equals("index.json", ignoreCase = true)) continue
                    val id = imageIdFromFileName(name) ?: continue
                    out.add(Triple(entry, id, dir))
                }
            }
        }
        walk(rootDoc, 0)
        return out
    }

    /**
     * Refreshes optimization progress by scanning the configured library folder.
     */
    suspend fun refreshOptimizationProgress() = withContext(Dispatchers.IO) {
        val folderUriString = preferences.localFolderUriFlow.first()
        if (folderUriString.isBlank()) {
            _optimizationProgress.value = OptimizationProgress(0, 0, false, 0, "No library folder selected. Use Settings → Select Save Folder.")
            return@withContext
        }
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
            if (rootDoc == null || !rootDoc.exists()) {
                _optimizationProgress.value = OptimizationProgress(0, 0, false, 0, "Library folder not found. Re-select it in Settings.")
                return@withContext
            }
            val unreadable = !rootDoc.canRead()
            if (unreadable) {
                val persisted = try {
                    context.contentResolver.persistedUriPermissions.joinToString { "${it.uri} r=${it.isReadPermission} w=${it.isWritePermission}" }
                } catch (e: Exception) {
                    "unavailable: ${e.message}"
                }
                Log.w("WallpaperRepo", "Library folder not readable: $folderUriString (re-select the folder in Settings) persisted=[$persisted]")
            }
            val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
            val files = collectLibraryFiles(rootDoc)
            Log.d("WallpaperRepo", "Library scan found ${files.size} image file(s) under ${rootDoc.uri}")
            val optimizedCount = files.count { (file, id) -> isFileOptimized(file, id, optimizedIds) }
            val scanError = when {
                unreadable -> "Library folder unreadable — re-select it in Settings."
                files.isEmpty() -> "Found 0 image files in the selected folder (root + subfolders)."
                else -> _optimizationProgress.value.lastError
            }
            _optimizationProgress.value = _optimizationProgress.value.copy(
                optimized = optimizedCount,
                total = files.size,
                failed = (files.size - optimizedCount).coerceAtLeast(0),
                lastError = scanError
            )
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error refreshing optimization progress", e)
            _optimizationProgress.value = _optimizationProgress.value.copy(
                lastError = "Could not scan library folder: ${e.message}"
            )
        }
    }

    /**
     * Optimizes all library wallpaper files in the background (4K max, WebP format) until complete.
     * Runs by default; progress is exposed via [optimizationProgress].
     */
    suspend fun compressUnoptimizedWallpapers() = withContext(Dispatchers.IO) {
        optimizationMutex.withLock {
            val folderUriString = preferences.localFolderUriFlow.first()
            if (folderUriString.isBlank()) {
                _optimizationProgress.value = OptimizationProgress(0, 0, false)
                return@withLock
            }

            _optimizationProgress.value = _optimizationProgress.value.copy(isRunning = true)
            try {
                val folderUri = Uri.parse(folderUriString)
                val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                if (rootDoc == null || !rootDoc.exists()) {
                    Log.w("WallpaperRepo", "Library folder not found: $folderUriString")
                    refreshOptimizationProgress()
                    _optimizationProgress.value = _optimizationProgress.value.copy(isRunning = false)
                    return@withLock
                }
                // NOTE: DocumentFile.canWrite() is unreliable on external
                // storage (e.g. /storage/emulated/0/Pictures/Walle) — it can
                // report false even with a persisted read/write grant.
                // Only warn here; each file's write outcome is the real
                // signal. (If permission was lost, re-select the folder in
                // Settings to restore read/write access.)
                if (!rootDoc.canWrite()) {
                    Log.w("WallpaperRepo", "Library folder reports canWrite=false; attempting anyway")
                }

                val optimizedIds = preferences.optimizedWallpaperIdsFlow.first().toMutableSet()

                // Collect every image file in the library (recursive, so
                // files at any depth are included) so duplicates and
                // previously mis-marked large originals are revisited.
                data class PendingFile(val file: DocumentFile, val id: String, val parentDir: DocumentFile)
                val allFiles = collectLibraryFiles(rootDoc).map { (file, id, parentDir) ->
                    PendingFile(file, id, parentDir)
                }
                Log.d("WallpaperRepo", "Optimization pass queued ${allFiles.size} file(s); running=true")

                var optimizedCount = allFiles.count { isFileOptimized(it.file, it.id, optimizedIds) }
                var failedCount = allFiles.size - optimizedCount
                var lastErrorMsg = _optimizationProgress.value.lastError
                _optimizationProgress.value = OptimizationProgress(optimizedCount, allFiles.size, true, failedCount, lastErrorMsg)

                for (item in allFiles) {
                    if (isFileOptimized(item.file, item.id, optimizedIds)) continue
                    // Re-fetch the file fresh from its parent so we don't
                    // use a stale URI from the scan snapshot.
                    val freshFile = try {
                        item.parentDir.findFile(item.file.name ?: "") ?: item.file
                    } catch (e: Exception) {
                        item.file
                    }
                    val optimizedFile = try {
                        com.example.util.ImageCompressionHelper.optimizeDocumentFile(
                            context = context,
                            docFile = freshFile,
                            parentDir = item.parentDir
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e("WallpaperRepo", "Error optimizing ${item.file.name}", e)
                        null
                    }
                    if (optimizedFile != null) {
                        optimizedIds.add(item.id)
                        preferences.markWallpaperOptimized(item.id)
                        // Point the DB at the replaced .webp file so later
                        // face-detection / sync does not hit a deleted URI.
                        try {
                            if (favoriteDao.isFavoriteDirect(item.id)) {
                                favoriteDao.updateLocalFilePath(item.id, optimizedFile.uri.toString())
                            }
                        } catch (e: Exception) {
                            Log.e("WallpaperRepo", "Error updating local path for ${item.id}", e)
                        }
                        if (optimizedCount < allFiles.size) optimizedCount += 1
                        failedCount = (allFiles.size - optimizedCount).coerceAtLeast(0)
                    } else {
                        // Leave unmarked so the file is retried on the next run.
                        val reason = com.example.util.ImageCompressionHelper.lastFailureReason.ifBlank {
                            "Could not convert ${item.file.name}."
                        }
                        lastErrorMsg = reason
                        Log.w("WallpaperRepo", "Optimization did not complete for ${item.file.name}; will retry — $reason")
                    }
                    _optimizationProgress.value = OptimizationProgress(optimizedCount, allFiles.size, true, failedCount, lastErrorMsg)
                }

                // Update metadata index.json with optimized status
                updateFolderMetadataIndex(rootDoc)
                _optimizationProgress.value = OptimizationProgress(optimizedCount, allFiles.size, false, failedCount, lastErrorMsg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("WallpaperRepo", "Error compressing unoptimized wallpapers", e)
                refreshOptimizationProgress()
                _optimizationProgress.value = _optimizationProgress.value.copy(isRunning = false)
            }
        }
    }

    /**
     * Optimizes a single saved wallpaper file in the background (4K max, WebP format).
     * Only marks as optimized after the WebP replacement actually succeeds.
     */
    suspend fun optimizeSingleWallpaperFile(
        targetFile: DocumentFile,
        id: String,
        parentDir: DocumentFile? = null
    ) = withContext(Dispatchers.IO) {
        optimizationMutex.withLock {
            try {
                val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
                if (isFileOptimized(targetFile, id, optimizedIds)) {
                    refreshOptimizationProgress()
                    return@withLock
                }
                val optimizedFile = com.example.util.ImageCompressionHelper.optimizeDocumentFile(
                    context = context,
                    docFile = targetFile,
                    parentDir = parentDir ?: targetFile.parentFile
                )
                if (optimizedFile != null) {
                    preferences.markWallpaperOptimized(id)
                    try {
                        if (favoriteDao.isFavoriteDirect(id)) {
                            favoriteDao.updateLocalFilePath(id, optimizedFile.uri.toString())
                        }
                    } catch (e: Exception) {
                        Log.e("WallpaperRepo", "Error updating local path for $id", e)
                    }
                }
                refreshOptimizationProgress()
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error optimizing single wallpaper $id", e)
            }
        }
    }

    /**
     * Searches Wallhaven with pagination support for the Discover grid.
     */
    suspend fun searchWallpapersPaged(
        query: String?,
        category: String,
        purity: String,
        isNsfwMode: Boolean,
        page: Int = 1
    ): Result<com.example.data.model.WallhavenSearchResult> = withContext(Dispatchers.IO) {
        val apiKey = preferences.apiKeyFlow.first().takeIf { it.isNotBlank() }

        val categoriesCode = when (category.lowercase()) {
            "anime" -> "010"
            "people" -> "001"
            else -> "111"
        }

        val purityCode = if (!isNsfwMode) {
            "100"
        } else {
            if (apiKey != null) {
                when (purity.lowercase()) {
                    "sketchy" -> "010"
                    "nsfw" -> "001"
                    else -> "011"
                }
            } else {
                "010"
            }
        }

        val isLikeQuery = query?.trim()?.startsWith("like:", ignoreCase = true) == true
        val wallhavenQuery = if (isLikeQuery) {
            val cleanId = extractWallhavenId(query!!)
            val localFav = favoriteDao.getFavoriteById(cleanId)
            val localFavTags = localFav?.tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val currentWp = preferences.currentWallpaperFlow.first()
            val currentWpTags = if (currentWp.id == cleanId || extractWallhavenId(currentWp.url) == cleanId) currentWp.tags else emptyList()

            val knownTags = when {
                localFavTags.isNotEmpty() -> localFavTags
                currentWpTags.isNotEmpty() -> currentWpTags
                else -> {
                    try {
                        val details = apiService.getWallpaperDetails(cleanId, apiKey)
                        details.data?.tags?.map { it.name } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }

            if (knownTags.isNotEmpty()) {
                val filteredTags = knownTags.filter { tag ->
                    val lower = tag.lowercase()
                    !lower.contains("watermark") && !lower.contains("wallpaper") && !lower.contains("simple background")
                }
                val tagsToUse = if (filteredTags.isNotEmpty()) filteredTags else knownTags
                tagsToUse.take(3).joinToString(" ")
            } else {
                null
            }
        } else {
            buildWallhavenQuery(query)
        }

        try {
            // Single timeout layer (OkHttp callTimeout only). A second
            // coroutine withTimeout here cancels mid-flight and poisons
            // the pool Coil shares, which hurts slower WiFi paths first.
            val sortingMode = if (wallhavenQuery.isNullOrBlank()) "toplist" else "relevance"
            val response = try {
                apiService.searchWallpapers(
                    query = wallhavenQuery,
                    categories = categoriesCode,
                    purity = purityCode,
                    sorting = sortingMode,
                    order = "desc",
                    page = page,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                if (apiKey != null) {
                    // Fallback without apiKey if key is invalid or blocked
                    apiService.searchWallpapers(
                        query = wallhavenQuery,
                        categories = categoriesCode,
                        purity = "100",
                        sorting = sortingMode,
                        order = "desc",
                        page = page,
                        apiKey = null
                    )
                } else {
                    throw e
                }
            }

            val meta = response.meta
            val currentPage = meta?.currentPage ?: page
            val lastPage = meta?.lastPage ?: page
            val total = meta?.total ?: response.data.size

            val favoriteIds = favoriteDao.getAllFavoriteIds().toSet()

            val wallpapers = response.data.map { item ->
                val fullUrl = item.path.ifBlank { item.url }
                val thumb = item.thumbs?.large ?: item.thumbs?.original ?: item.thumbs?.small ?: fullUrl
                val isFav = item.id in favoriteIds
                val cachedOffset = getCachedOffset(item.id)
                val favOffset = if (isFav) favoriteDao.getFavoriteById(item.id)?.horizontalOffset else null
                val offset = cachedOffset ?: favOffset?.takeIf { it in 0f..1f } ?: 0.5f

                Wallpaper(
                    id = item.id,
                    fullUrl = fullUrl,
                    thumbUrl = thumb,
                    purity = item.purity,
                    category = item.category,
                    resolution = item.resolution,
                    horizontalOffset = offset,
                    isFavorite = isFav,
                    tags = item.tags?.map { it.name } ?: emptyList()
                )
            }

            Result.success(com.example.data.model.WallhavenSearchResult(wallpapers, currentPage, lastPage, total))
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error searching wallpapers paged", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches and applies the next wallpaper with minimal latency.
     * 1) Face detection model ONLY determines positioning the first time an image is loaded,
     *    never again for the same image (checked against memory & persistent cache).
     * 2) If no tag is selected, shuffles saved favorites (preserving existing positioning).
     */
    suspend fun fetchAndApplyNextWallpaper(): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val tags = if (isNsfw) preferences.nsfwTagsFlow.first() else preferences.sfwTagsFlow.first()
            val existingIds = favoriteDao.getAllFavoriteIds().toSet()

            var candidateWallpaper: Wallpaper? = null

            if (tags.isEmpty()) {
                // If no tag is selected, shuffled photos saved from favorites
                val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
                val randomFav = favoriteDao.getRandomFavorite(purities)
                if (randomFav != null) {
                    val finalOffset = getOrDeterminePositionOffset(
                        randomFav.id,
                        randomFav.localFilePath ?: randomFav.thumbUrl.ifBlank { randomFav.url }
                    )
                    if (randomFav.horizontalOffset !in 0f..1f) {
                        favoriteDao.updateCropOffset(randomFav.id, finalOffset)
                    }
                    val favTags = if (randomFav.tags.isNotBlank()) {
                        randomFav.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }
                    candidateWallpaper = Wallpaper(
                        id = randomFav.id,
                        fullUrl = randomFav.url,
                        thumbUrl = randomFav.thumbUrl,
                        purity = randomFav.purity,
                        category = randomFav.category,
                        resolution = randomFav.resolution,
                        horizontalOffset = finalOffset,
                        isFavorite = true,
                        tags = favTags
                    )
                }
            }

            if (candidateWallpaper == null) {
                // Query Wallhaven API (New image!)
                val fetched = fetchFromWallhaven(isNsfw, tags, existingIds)
                if (fetched != null) {
                    // Face detection runs ONLY the first time an image is loaded,
                    // using the lightweight thumbnail to prevent high network latency.
                    val smartOffset = getOrDeterminePositionOffset(
                        fetched.id,
                        fetched.thumbUrl.ifBlank { fetched.fullUrl }
                    )
                    candidateWallpaper = fetched.copy(horizontalOffset = smartOffset)
                }
            }

            if (candidateWallpaper != null) {
                // Update current wallpaper state with final pre-calculated offset and tags
                preferences.setCurrentWallpaper(
                    id = candidateWallpaper.id,
                    url = candidateWallpaper.fullUrl,
                    thumbUrl = candidateWallpaper.thumbUrl,
                    purity = candidateWallpaper.purity,
                    category = candidateWallpaper.category,
                    offset = candidateWallpaper.horizontalOffset,
                    tags = candidateWallpaper.tags
                )

                // Broadcast update event to Live Wallpaper
                notifyWallpaperChanged()

                Result.success(candidateWallpaper)
            } else {
                Result.failure(Exception("No wallpapers found"))
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error fetching wallpaper", e)
            Result.failure(e)
        }
    }

    /**
     * Initial install fetch: Guarantees fetching an image matching "nature" for SFW mode
     * or "model" for NSFW mode when the app is first installed.
     */
    suspend fun fetchAndApplyInitialWallpaper(isNsfw: Boolean, initialTag: String): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val existingIds = favoriteDao.getAllFavoriteIds().toSet()
            val fetched = fetchFromWallhaven(isNsfw, setOf(initialTag), existingIds)
            if (fetched != null) {
                val smartOffset = getOrDeterminePositionOffset(
                    fetched.id,
                    fetched.thumbUrl.ifBlank { fetched.fullUrl }
                )
                val finalWallpaper = fetched.copy(horizontalOffset = smartOffset)
                preferences.setCurrentWallpaper(
                    id = finalWallpaper.id,
                    url = finalWallpaper.fullUrl,
                    thumbUrl = finalWallpaper.thumbUrl,
                    purity = finalWallpaper.purity,
                    category = finalWallpaper.category,
                    offset = finalWallpaper.horizontalOffset,
                    tags = finalWallpaper.tags
                )
                notifyWallpaperChanged()
                Result.success(finalWallpaper)
            } else {
                fetchAndApplyNextWallpaper()
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error fetching initial wallpaper", e)
            fetchAndApplyNextWallpaper()
        }
    }

    private suspend fun fetchFromWallhaven(
        isNsfw: Boolean,
        tags: Set<String>,
        existingIds: Set<String>
    ): Wallpaper? {
        val apiKey = preferences.apiKeyFlow.first().takeIf { it.isNotBlank() }

        val categorySelection = if (isNsfw) {
            preferences.nsfwCategoryFlow.first()
        } else {
            preferences.sfwCategoryFlow.first()
        }
        val categoriesCode = when (categorySelection) {
            "anime" -> "010"
            "people" -> "001"
            else -> "111"
        }

        // Purity code:
        // SFW mode: "100" (SFW only)
        // NSFW mode:
        // - With API key: "010" (sketchy), "001" (nsfw), or "011" (both)
        // - Without API key: Wallhaven returns 0 items for "001", so fallback to "010" (sketchy)
        val nsfwPuritySelection = preferences.nsfwPurityFlow.first()
        val purityCode = if (!isNsfw) {
            "100"
        } else {
            if (apiKey != null) {
                when (nsfwPuritySelection) {
                    "sketchy" -> "010"
                    "nsfw" -> "001"
                    else -> "011"
                }
            } else {
                "010"
            }
        }

        val targetTags = if (tags.isNotEmpty()) {
            tags.toList()
        } else {
            listOf(if (isNsfw) "model" else "nature")
        }

        // Single unified search call: all tags are included as plain
        // terms (no + / - operators) with relevance sorting so the
        // broadest relevant result set is returned in one request.
        // Category and purity codes above are unchanged.
        val wallhavenQuery = buildWallhavenQuery(targetTags)

        val response = try {
            apiService.searchWallpapers(
                query = wallhavenQuery,
                categories = categoriesCode,
                purity = purityCode,
                sorting = "relevance",
                order = "desc",
                apiKey = apiKey
            )
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Wallhaven search failed for '$wallhavenQuery': ${e.message}", e)
            return null
        }

        if (response.data.isEmpty()) return null

        // Select a fresh wallpaper, prioritizing non-existing IDs, with random selection among results
        val unseenItems = response.data.filter { it.id !in existingIds }
        val item = unseenItems.randomOrNull() ?: response.data.randomOrNull() ?: return null

        val fullUrl = item.path.ifBlank { item.url }
        val thumb = item.thumbs?.large ?: item.thumbs?.original ?: item.thumbs?.small ?: fullUrl

        // Direct tags or target query tags (avoiding synchronous blocking detail call)
        val directTags = item.tags?.map { it.name } ?: emptyList()
        val initialTags = if (directTags.isNotEmpty()) directTags else targetTags

        // Pre-warm Coil cache for full image in background
        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(fullUrl)
                .build()
        )

        return Wallpaper(
            id = item.id,
            fullUrl = fullUrl,
            thumbUrl = thumb,
            purity = item.purity,
            category = item.category,
            resolution = item.resolution,
            horizontalOffset = 0.5f,
            isFavorite = false,
            tags = initialTags
        )
    }

    /**
     * Runs ML Kit face detection on the image thumbnail or local stream to position the focus
     * to the right side of the screen within the device's aspect ratio.
     * Uses small software bitmaps to guarantee sub-50ms execution.
     */
    suspend fun detectFaceOffset(imageSource: String): Float = withContext(Dispatchers.IO) {
        if (imageSource.isBlank()) return@withContext 0.5f

        try {
            kotlinx.coroutines.withTimeout(3500L) {
                var bitmap: Bitmap? = null

                // 1. If it's a local content:// or file:// URI
                if (imageSource.startsWith("content://") || imageSource.startsWith("file://")) {
                    try {
                        val uri = Uri.parse(imageSource)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(input, null, bounds)
                        }

                        var sample = 1
                        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                        while (maxDim / (sample * 2) >= 720) {
                            sample *= 2
                        }

                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            bitmap = BitmapFactory.decodeStream(input, null, opts)
                        }
                    } catch (e: Exception) {
                        Log.e("WallpaperRepo", "Error decoding local bitmap for face detection", e)
                    }
                }

                // 2. If it's a remote URL or local decoding failed
                if (bitmap == null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(imageSource)
                            .size(720, 720)
                            .allowHardware(false) // Must be software bitmap for ML Kit
                            .build()
                        val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                        bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    } catch (e: Exception) {
                        Log.e("WallpaperRepo", "Error loading bitmap for face detection", e)
                    }
                }

                val finalBitmap = bitmap
                if (finalBitmap != null) {
                    val metrics = context.resources.displayMetrics
                    val deviceAspect = if (metrics.heightPixels > 0) {
                        metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
                    } else {
                        9f / 19.5f
                    }
                    FaceDetectorHelper.calculateSmartCropOffset(finalBitmap, deviceAspect)
                } else {
                    0.5f
                }
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error in detectFaceOffset", e)
            0.5f
        }
    }

    /**
     * Internal thumbnail storage helper.
     * Generates and stores thumbnails in the app's internal storage directory,
     * ensuring high-performance grid loading without writing to the user's selected folder.
     */
    fun getInternalThumbnailFile(id: String): File {
        val thumbsDir = File(context.filesDir, "favorites_thumbs")
        if (!thumbsDir.exists()) {
            thumbsDir.mkdirs()
        }
        return File(thumbsDir, "$id.webp")
    }

    suspend fun generateInternalThumbnail(
        id: String,
        sourceUri: Uri?,
        sourceUrl: String?
    ): String = withContext(Dispatchers.IO) {
        val thumbFile = getInternalThumbnailFile(id)
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return@withContext Uri.fromFile(thumbFile).toString()
        }

        try {
            var bitmap: Bitmap? = null

            // 1. Decode from local document URI if available
            if (sourceUri != null) {
                try {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, boundsOptions)
                    }

                    val targetSize = 720
                    var sampleSize = 1
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    while (maxDim / (sampleSize * 2) >= targetSize) {
                        sampleSize *= 2
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }

                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        bitmap = BitmapFactory.decodeStream(input, null, decodeOptions)
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error decoding local bitmap for thumbnail $id", e)
                }
            }

            // 2. Fallback to imageLoader if sourceUrl provided
            if (bitmap == null && !sourceUrl.isNullOrBlank()) {
                val request = ImageRequest.Builder(context)
                    .data(sourceUrl)
                    .size(720, 1280)
                    .allowHardware(false)
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }

            val finalBitmap = bitmap
            if (finalBitmap != null) {
                FileOutputStream(thumbFile).use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out)
                    } else {
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }
                return@withContext Uri.fromFile(thumbFile).toString()
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to generate internal thumbnail for $id", e)
        }

        return@withContext sourceUrl ?: ""
    }

    /**
     * Loads the existing index.json and wallpaper files from the selected folder into the app.
     * If positioning data exists in index.json, it is preserved. If not, it is marked with -1f
     * so that face detection runs upon application, preventing jank.
     * Thumbnails are generated strictly into the app's internal storage for high performance.
     */
    suspend fun syncAndLoadFromSelectedFolder(folderUriString: String) = withContext(Dispatchers.IO) {
        if (folderUriString.isBlank()) return@withContext

        try {
            folderMutex.withLock {
            val folderUri = Uri.parse(folderUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withLock
            if (!rootDoc.canRead()) return@withLock

            // Ensure .nomedia in root to prevent recent files clutter
            if (rootDoc.canWrite() && rootDoc.findFile(".nomedia") == null) {
                try {
                    rootDoc.createFile("application/octet-stream", ".nomedia")
                } catch (e: Exception) {
                    // Ignore
                }
            }

            val indexedItemsMap = mutableMapOf<String, JSONObject>()

            // 1. Read existing index.json if present
            val indexFile = rootDoc.findFile("index.json")
            if (indexFile != null && indexFile.exists()) {
                try {
                    val jsonString = context.contentResolver.openInputStream(indexFile.uri)?.use { stream ->
                        stream.bufferedReader().use { it.readText() }
                    }
                    if (!jsonString.isNullOrBlank()) {
                        val rootJson = JSONObject(jsonString)
                        val array = rootJson.optJSONArray("wallpapers")
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val id = item.optString("id")
                                if (id.isNotBlank()) {
                                    indexedItemsMap[id] = item
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error parsing existing index.json", e)
                }
            }

            // 2. Scan subdirectories: sfw, nsfw, and root
            val dirsToScan = listOf(
                rootDoc.findFile("sfw") to "sfw",
                rootDoc.findFile("nsfw") to "nsfw",
                rootDoc to "sfw"
            )

            val discoveredFiles = mutableMapOf<String, Pair<DocumentFile, String>>()

            for ((dir, defaultPurity) in dirsToScan) {
                if (dir == null || !dir.exists() || !dir.isDirectory) continue
                for (file in dir.listFiles()) {
                    if (file.isDirectory) continue
                    val name = file.name ?: continue
                    if (name.equals("index.json", ignoreCase = true)) continue
                    val lower = name.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                        val baseId = name.substringBeforeLast('.')
                        val id = if (baseId.startsWith("wallhaven-", ignoreCase = true)) {
                            baseId.substringAfter("wallhaven-")
                        } else {
                            baseId
                        }
                        if (!discoveredFiles.containsKey(id)) {
                            discoveredFiles[id] = file to defaultPurity
                        }
                    }
                }
            }

            // 3. Prune index items without a corresponding image file.
            // A favorite removed in-app (or deleted via Files) must not be
            // resurrected from index.json alone — that left a card with no
            // image. Delete the DB row, internal thumbnail and cached offset
            // so both the view (Room Flow) and the index stay consistent.
            val orphanIndexIds = indexedItemsMap.keys - discoveredFiles.keys
            for (id in orphanIndexIds) {
                try {
                    favoriteDao.deleteById(id)
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error removing orphan $id from DB", e)
                }
                try {
                    val thumbFile = getInternalThumbnailFile(id)
                    if (thumbFile.exists()) {
                        thumbFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error deleting orphan thumbnail for $id", e)
                }
                memoryOffsetCache.remove(id)
                positioningPrefs.edit().remove(id).apply()
            }

            // 4. Process each scanned file: restore stored positioning and fast-insert into Room
            val favoritesToInsert = mutableListOf<FavoriteWallpaper>()
            val unpositionedItems = mutableListOf<Pair<String, String>>() // id to imageSource

            for (id in discoveredFiles.keys) {
                val indexJson = indexedItemsMap[id]
                val localFileInfo = discoveredFiles[id]

                val purity = indexJson?.optString("purity")
                    ?: localFileInfo?.second
                    ?: "sfw"
                val category = indexJson?.optString("category") ?: "all"

                // Check stored positioning: index.json takes highest priority, then cached prefs, fallback 0.5f
                val rawOffset = if (indexJson != null && indexJson.has("horizontalOffset")) {
                    indexJson.optDouble("horizontalOffset", Double.NaN)
                } else {
                    Double.NaN
                }

                val hasValidStoredOffset = !rawOffset.isNaN() && rawOffset in 0.0..1.0
                val horizontalOffset = if (hasValidStoredOffset) {
                    val off = rawOffset.toFloat()
                    setCachedOffset(id, off)
                    off
                } else {
                    val cached = getCachedOffset(id)
                    if (cached != null) {
                        cached
                    } else {
                        val localUriStr = localFileInfo?.first?.uri?.toString() ?: ""
                        unpositionedItems.add(id to localUriStr)
                        0.5f
                    }
                }

                val timestamp = indexJson?.optLong("timestamp", System.currentTimeMillis()) ?: System.currentTimeMillis()
                val localFileUri = localFileInfo?.first?.uri
                val url = indexJson?.optString("url")
                    ?: localFileUri?.toString()
                    ?: "https://w.wallhaven.cc/full/${id.take(2)}/wallhaven-$id.jpg"

                // Use internal thumbnail if already created; otherwise use localFileUri immediately
                // so the grid displays instantly without waiting for thumbnail generation!
                val thumbFile = getInternalThumbnailFile(id)
                val initialThumb = if (thumbFile.exists() && thumbFile.length() > 0) {
                    Uri.fromFile(thumbFile).toString()
                } else {
                    localFileUri?.toString() ?: url
                }

                val fav = FavoriteWallpaper(
                    id = id,
                    url = url,
                    thumbUrl = initialThumb,
                    purity = purity,
                    category = category,
                    horizontalOffset = horizontalOffset,
                    addedTimestamp = timestamp,
                    localFilePath = localFileUri?.toString()
                )

                favoritesToInsert.add(fav)
            }

            // Immediately insert all favorites into the DB so the UI populates instantaneously
            for (fav in favoritesToInsert) {
                favoriteDao.insertFavorite(fav)
            }

            // 5. Update/refresh index.json in the user folder so it reflects all favorites
            updateFolderMetadataIndex(rootDoc)
            notifyWallpaperChanged()

            // 6. Asynchronously optimize thumbnails in background and run face detection on unpositioned items
            CoroutineScope(Dispatchers.IO).launch {
                for (fav in favoritesToInsert) {
                    try {
                        val thumbFile = getInternalThumbnailFile(fav.id)
                        if (!thumbFile.exists() || thumbFile.length() == 0L) {
                            val localUri = fav.localFilePath?.let { Uri.parse(it) }
                            val internalThumb = generateInternalThumbnail(
                                id = fav.id,
                                sourceUri = localUri,
                                sourceUrl = fav.url
                            )
                            if (internalThumb.isNotBlank() && internalThumb != fav.thumbUrl) {
                                favoriteDao.insertFavorite(fav.copy(thumbUrl = internalThumb))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WallpaperRepo", "Error generating background thumbnail for ${fav.id}", e)
                    }
                }

                if (unpositionedItems.isNotEmpty()) {
                    var anyOffsetUpdated = false
                    for ((id, src) in unpositionedItems) {
                        try {
                            val detected = detectFaceOffset(src)
                            setCachedOffset(id, detected)
                            favoriteDao.updateCropOffset(id, detected)
                            anyOffsetUpdated = true
                        } catch (e: Exception) {
                            Log.e("WallpaperRepo", "Error detecting background offset for $id", e)
                        }
                    }
                    if (anyOffsetUpdated) {
                        try {
                            folderMutex.withLock {
                                updateFolderMetadataIndex(rootDoc)
                            }
                            notifyWallpaperChanged()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
            }
            // Automatically optimize library images (4K max, WebP) in the background by default
            refreshOptimizationProgress()
            CoroutineScope(Dispatchers.IO).launch { compressUnoptimizedWallpapers() }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error syncing and loading from selected folder", e)
        }
    }

    suspend fun saveWallpaperToLocalFolder(fav: FavoriteWallpaper) = withContext(Dispatchers.IO) {
        val folderUriString = preferences.localFolderUriFlow.first()
        if (folderUriString.isBlank()) return@withContext

        try {
            val folderUri = Uri.parse(folderUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext

            // Ensure .nomedia in root folder to prevent cluttering recent files in files app
            if (rootDoc.findFile(".nomedia") == null) {
                try {
                    rootDoc.createFile("application/octet-stream", ".nomedia")
                } catch (e: Exception) {
                    // Ignore
                }
            }

            val subfolderName = if (fav.purity == "sfw") "sfw" else "nsfw"
            var subDir = rootDoc.findFile(subfolderName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = rootDoc.createDirectory(subfolderName) ?: return@withContext
            }

            // Ensure .nomedia in subfolder
            if (subDir.findFile(".nomedia") == null) {
                try {
                    subDir.createFile("application/octet-stream", ".nomedia")
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Reuse an existing library file for this ID in any image
            // extension (including an already-optimized .webp) instead of
            // downloading a second large original next to it.
            var targetFile = subDir.listFiles().firstOrNull { f ->
                if (f.isDirectory) false
                else {
                    val fName = f.name ?: return@firstOrNull false
                    imageIdFromFileName(fName) == fav.id
                }
            }
            if (targetFile == null) {
                // Determine extension (.png or .jpg) from url
                val isPng = fav.url.endsWith(".png", ignoreCase = true)
                val extension = if (isPng) ".png" else ".jpg"
                val mimeType = if (isPng) "image/png" else "image/jpeg"
                val fileName = "${fav.id}$extension"
                targetFile = subDir.createFile(mimeType, fileName)
                if (targetFile != null) {
                    // Stream the original untouched full-resolution file from wallhaven
                    val request = Request.Builder().url(fav.url).build()
                    val response = NetworkClient.okHttpClient.newCall(request).execute()
                    response.body?.byteStream()?.use { inputStream ->
                        context.contentResolver.openOutputStream(targetFile.uri)?.use { outStream ->
                            inputStream.copyTo(outStream)
                        }
                    }
                }
            }

            // Generate/cache internal thumbnail in app internal storage (not in user folder)
            val internalThumb = generateInternalThumbnail(
                id = fav.id,
                sourceUri = targetFile?.uri,
                sourceUrl = fav.url
            )
            if (internalThumb.isNotBlank() && internalThumb != fav.thumbUrl) {
                favoriteDao.insertFavorite(fav.copy(thumbUrl = internalThumb, localFilePath = targetFile?.uri?.toString()))
            }

            // Update metadata index.json in the root folder
            updateFolderMetadataIndex(rootDoc)

            // Optimize newly saved wallpaper in background (4K max, WebP) by
            // default. This covers favorites added from Discover, since
            // toggleFavorite() funnels through saveWallpaperToLocalFolder().
            val savedFile = targetFile
            if (savedFile != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    optimizeSingleWallpaperFile(savedFile, fav.id, subDir)
                    compressUnoptimizedWallpapers()
                }
            } else {
                refreshOptimizationProgress()
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error saving wallpaper to user folder", e)
        }
    }

    private suspend fun updateFolderMetadataIndex(rootDoc: DocumentFile) {
        try {
            var indexFile = rootDoc.findFile("index.json")
            if (indexFile == null) {
                indexFile = rootDoc.createFile("application/json", "index.json") ?: return
            }

            val allFavs = favoriteDao.getAllFavoriteIds()
            val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
            val jsonArray = JSONArray()
            for (id in allFavs) {
                val fav = favoriteDao.getFavoriteById(id) ?: continue
                val itemObj = JSONObject().apply {
                    put("id", fav.id)
                    put("purity", fav.purity)
                    put("category", fav.category)
                    val offsetToPersist = if (fav.horizontalOffset in 0f..1f) {
                        fav.horizontalOffset
                    } else {
                        getCachedOffset(fav.id) ?: 0.5f
                    }
                    put("horizontalOffset", offsetToPersist.toDouble())
                    put("url", fav.url)
                    put("timestamp", fav.addedTimestamp)
                    put("optimized", optimizedIds.contains(fav.id))
                }
                jsonArray.put(itemObj)
            }

            val rootJson = JSONObject().apply {
                put("updatedAt", System.currentTimeMillis())
                put("count", jsonArray.length())
                put("wallpapers", jsonArray)
            }

            context.contentResolver.openOutputStream(indexFile.uri, "wt")?.use { out ->
                OutputStreamWriter(out).use { writer ->
                    writer.write(rootJson.toString(2))
                }
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to update index.json", e)
        }
    }

    fun notifyWallpaperChanged() {
        val intent = android.content.Intent("com.example.walle.ACTION_WALLPAPER_UPDATED")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}

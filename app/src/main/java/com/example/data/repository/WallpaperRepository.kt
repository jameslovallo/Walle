
package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.api.NetworkClient
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.local.CurrentWallpaperInfo
import com.example.data.local.FavoriteWallpaper
import com.example.data.model.Wallpaper
import com.example.domain.facedetection.FaceDetectorHelper
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

class WallpaperRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences
) {
    private val apiService = NetworkClient.apiService
    private val favoriteDao = database.favoriteDao()
    // Shared singleton (see WallEApplication.newImageLoader) so face
    // detection, prefetch, and Compose AsyncImages share one cache.
    private val imageLoader = Coil.imageLoader(context)
    private val positioningPrefs = context.getSharedPreferences("walle_positioning_cache", Context.MODE_PRIVATE)
    private val memoryOffsetCache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val folderMutex = Mutex()
    private val optimizationMutex = Mutex()

    companion object {
        fun buildWallhavenQuery(query: String?): String? {
            if (query.isNullOrBlank()) return null
            val trimmed = query.trim()
            if (trimmed.startsWith("like:", ignoreCase = true)) {
                val targetId = trimmed.substringAfter("like:").trim()
                return if (targetId.isNotBlank()) "like:$targetId" else null
            }
            return buildWallhavenQuery(trimmed.split(',', ' '))
        }

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

    /**
     * Stored offset: cache > DB row > 0.5. Never runs face detection.
     * Used for favorites (index/DB is truth) and cached Discover offsets.
     */
    suspend fun getStoredOffset(id: String): Float {
        getCachedOffset(id)?.let { return it }
        if (id.isNotBlank()) {
            val fav = favoriteDao.getFavoriteById(id)
            if (fav != null && fav.horizontalOffset in 0f..1f) {
                setCachedOffset(id, fav.horizontalOffset)
                return fav.horizontalOffset
            }
        }
        return 0.5f
    }

    /**
     * Face detection entry point for the Discover grid.
     * Only detects when the id is NOT already a favorite (favorites keep
     * their stored offset from the index/DB).
     */
    suspend fun lazyDetectOffset(id: String, thumbUrlOrSource: String): Float = withContext(Dispatchers.IO) {
        if (favoriteDao.isFavoriteDirect(id)) {
            return@withContext getStoredOffset(id)
        }
        getCachedOffset(id)?.let { return@withContext it }
        val detected = detectFaceOffset(thumbUrlOrSource)
        if (id.isNotBlank()) {
            setCachedOffset(id, detected)
        }
        detected
    }

    val isNsfwMode: Flow<Boolean> = preferences.isNsfwModeFlow
    val currentWallpaper: Flow<CurrentWallpaperInfo> = preferences.currentWallpaperFlow
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
            deleteFavorite(id)
            false
        } else {
            val effectiveOffset = getCachedOffset(id) ?: if (cropOffset in 0f..1f) cropOffset else 0.5f
            setCachedOffset(id, effectiveOffset)

            // Whole image is used everywhere; thumbUrl mirrors the full URL (kept for Room compat).
            val fav = FavoriteWallpaper(
                id = id,
                url = url,
                thumbUrl = url,
                purity = purity,
                category = category,
                horizontalOffset = effectiveOffset,
                addedTimestamp = System.currentTimeMillis(),
                tags = tags.joinToString(",")
            )
            favoriteDao.insertFavorite(fav)
            // Save to local user folder if configured (optimizes only this file).
            saveWallpaperToLocalFolder(fav)
            true
        }
    }

    suspend fun getFavoriteById(id: String): FavoriteWallpaper? = favoriteDao.getFavoriteById(id)

    suspend fun deleteFavorite(id: String) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext

        try {
            favoriteDao.deleteById(id)
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error deleting $id from DB", e)
        }
        memoryOffsetCache.remove(id)
        positioningPrefs.edit().remove(id).apply()
        preferences.removeOptimizedWallpaperId(id)

        CoroutineScope(Dispatchers.IO).launch {
            try {
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

                val folderUriString = preferences.localFolderUriFlow.first()
                if (folderUriString.isNotBlank()) {
                    folderMutex.withLock {
                        try {
                            val folderUri = Uri.parse(folderUriString)
                            val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                            if (rootDoc != null && rootDoc.exists() && rootDoc.canWrite()) {
                                val subDirs = listOfNotNull(rootDoc.findFile("sfw"), rootDoc.findFile("nsfw"), rootDoc)
                                for (dir in subDirs) {
                                    if (dir.exists() && dir.isDirectory) {
                                        for (f in dir.listFiles()) {
                                            val name = f.name ?: continue
                                            val cleanId = imageIdFromFileName(name) ?: continue
                                            if (cleanId.equals(id, ignoreCase = true)) {
                                                f.delete()
                                            }
                                        }
                                    }
                                }
                                updateFolderMetadataIndex(rootDoc)
                            }
                        } catch (e: Exception) {
                            Log.e("WallpaperRepo", "Error deleting file from local folder for $id", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error in async cleanup for deleted favorite $id", e)
            }
        }
    }

    suspend fun updateCropOffset(id: String, offset: Float) = withContext(Dispatchers.IO) {
        val clamped = offset.coerceIn(0f, 1f)
        setCachedOffset(id, clamped)
        if (preferences.currentWallpaperFlow.first().id == id) {
            preferences.updateCurrentWallpaperOffset(clamped)
        }
        val isFav = favoriteDao.isFavoriteDirect(id)
        if (isFav) {
            favoriteDao.updateCropOffset(id, clamped)
            val folderUriString = preferences.localFolderUriFlow.first()
            if (folderUriString.isNotBlank()) {
                try {
                    val folderUri = Uri.parse(folderUriString)
                    val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                    if (rootDoc != null && rootDoc.exists() && rootDoc.canWrite()) {
                        // Never queue the drag's writer behind a long folder
                        // load: if folderMutex is busy, skip the SAF rewrite —
                        // the DB already holds the offset and the next sync
                        // will rewrite index.json from it.
                        if (folderMutex.tryLock()) {
                            try {
                                updateFolderMetadataIndex(rootDoc)
                            } finally {
                                folderMutex.unlock()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error updating index.json on offset update", e)
                }
            }
        }
    }

    /**
     * Applies a favorite as current wallpaper using its stored offset.
     * Favorites never run face detection.
     */
    suspend fun applyFavorite(fav: FavoriteWallpaper) = withContext(Dispatchers.IO) {
        val finalOffset = if (fav.horizontalOffset in 0f..1f) {
            fav.horizontalOffset
        } else {
            getStoredOffset(fav.id)
        }
        setCachedOffset(fav.id, finalOffset)
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
            val others = favorites.filter { it.id != current.id }
            val candidate = others.randomOrNull() ?: favorites.random()

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

    /**
     * Single entry point for SFW<->NSFW mode switching.
     * Persists the new mode, then immediately applies a wallpaper from the
     * new category — a saved favorite if one exists, otherwise a fresh
     * Wallhaven fetch. Used by both the Quick Settings tile and the
     * in-app top-navigation switcher so they behave identically.
     */
    suspend fun switchModeAndApply(nextMode: Boolean) = withContext(Dispatchers.IO) {
        preferences.setNsfwMode(nextMode)
        val favoriteResult = applyNextScheduledFavoriteWallpaper()
        if (favoriteResult.isFailure) {
            fetchAndApplyNextWallpaper()
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

    private fun isFileOptimized(file: DocumentFile, id: String, optimizedIds: Set<String>): Boolean {
        if (id !in optimizedIds) return false
        if (file.name?.lowercase()?.endsWith(".webp") == true) return true
        return hasWebPMagic(file)
    }

    /**
     * Map of normalized id -> (file, purity) for images directly in
     * sfw/, nsfw/, and the selected root. Used only to check existence
     * for index entries during import.
     */
    private fun buildLocalFileMap(rootDoc: DocumentFile): Map<String, Pair<DocumentFile, String>> {
        val map = mutableMapOf<String, Pair<DocumentFile, String>>()
        val dirs = listOf(
            rootDoc.findFile("sfw") to "sfw",
            rootDoc.findFile("nsfw") to "nsfw",
            rootDoc to "sfw"
        )
        for ((dir, defaultPurity) in dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory) continue
            for (file in dir.listFiles()) {
                if (file.isDirectory) continue
                val name = file.name ?: continue
                if (name.equals("index.json", ignoreCase = true)) continue
                val id = imageIdFromFileName(name) ?: continue
                if (!map.containsKey(id)) {
                    map[id] = file to defaultPurity
                }
            }
        }
        return map
    }

    /**
     * Optimizes a single newly-favorited wallpaper file (4K max, WebP).
     * The only optimization entry point after simplification.
     */
    suspend fun optimizeSingleWallpaperFile(
        targetFile: DocumentFile,
        id: String,
        parentDir: DocumentFile? = null
    ) = withContext(Dispatchers.IO) {
        optimizationMutex.withLock {
            try {
                val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
                if (isFileOptimized(targetFile, id, optimizedIds)) return@withLock
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
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error optimizing single wallpaper $id", e)
            }
        }
    }

    /**
     * Startup check: scans the local library folder for any images that are
     * not yet stored as optimized WebP and optimizes them in the background.
     * Safe to call on every launch — already-optimized files are skipped.
     */
    suspend fun optimizeUnoptimizedWallpaperFiles() = withContext(Dispatchers.IO) {
        val folderUriString = preferences.localFolderUriFlow.first()
        if (folderUriString.isBlank()) return@withContext

        try {
            val folderUri = Uri.parse(folderUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            if (!rootDoc.exists() || !rootDoc.canRead()) return@withContext

            val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
            val dirs = listOf(
                rootDoc.findFile("sfw") to "sfw",
                rootDoc.findFile("nsfw") to "nsfw",
                rootDoc to "root"
            )

            for ((dir, _) in dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory) continue
                for (file in dir.listFiles()) {
                    if (file.isDirectory) continue
                    val name = file.name ?: continue
                    if (name.equals("index.json", ignoreCase = true)) continue
                    val lower = name.lowercase()
                    // Only consider images that are not already .webp.
                    if (lower.endsWith(".webp")) continue
                    if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) continue

                    val id = imageIdFromFileName(name) ?: continue
                    if (isFileOptimized(file, id, optimizedIds)) continue

                    optimizeSingleWallpaperFile(file, id, dir)
                }
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error optimizing unoptimized wallpaper files on startup", e)
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

            // Single query for the whole page instead of one per item.
            val favoriteOffsets = favoriteDao.getAllFavoritesDirect().associate { it.id to it.horizontalOffset }

            val wallpapers = response.data.map { item ->
                val fullUrl = item.path.ifBlank { item.url }
                val thumb = item.thumbs?.large ?: item.thumbs?.original ?: item.thumbs?.small ?: fullUrl
                val isFav = item.id in favoriteOffsets
                val cachedOffset = getCachedOffset(item.id)
                val favOffset = favoriteOffsets[item.id]
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
     * Uses stored offsets only; no face detection here.
     */
    suspend fun fetchAndApplyNextWallpaper(): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val tags = if (isNsfw) preferences.nsfwTagsFlow.first() else preferences.sfwTagsFlow.first()
            val existingIds = favoriteDao.getAllFavoriteIds().toSet()

            var candidateWallpaper: Wallpaper? = null

            if (tags.isEmpty()) {
                val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
                val randomFav = favoriteDao.getRandomFavorite(purities)
                if (randomFav != null) {
                    val finalOffset = if (randomFav.horizontalOffset in 0f..1f) {
                        randomFav.horizontalOffset
                    } else {
                        getStoredOffset(randomFav.id)
                    }
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
                val fetched = fetchFromWallhaven(isNsfw, tags, existingIds)
                if (fetched != null) {
                    val stored = getCachedOffset(fetched.id) ?: 0.5f
                    candidateWallpaper = fetched.copy(horizontalOffset = stored)
                }
            }

            if (candidateWallpaper != null) {
                preferences.setCurrentWallpaper(
                    id = candidateWallpaper.id,
                    url = candidateWallpaper.fullUrl,
                    thumbUrl = candidateWallpaper.thumbUrl,
                    purity = candidateWallpaper.purity,
                    category = candidateWallpaper.category,
                    offset = candidateWallpaper.horizontalOffset,
                    tags = candidateWallpaper.tags
                )

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
                val stored = getCachedOffset(fetched.id) ?: 0.5f
                val finalWallpaper = fetched.copy(horizontalOffset = stored)
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

        val unseenItems = response.data.filter { it.id !in existingIds }
        val item = unseenItems.randomOrNull() ?: response.data.randomOrNull() ?: return null

        val fullUrl = item.path.ifBlank { item.url }
        val thumb = item.thumbs?.large ?: item.thumbs?.original ?: item.thumbs?.small ?: fullUrl

        val directTags = item.tags?.map { it.name } ?: emptyList()
        val initialTags = if (directTags.isNotEmpty()) directTags else targetTags

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
     * Runs ML Kit face detection on the image thumbnail or local stream.
     * Only called from the Discover grid (via lazyDetectOffset) for
     * items that are not already favorites, plus one manual AI button.
     */
    suspend fun detectFaceOffset(imageSource: String): Float = withContext(Dispatchers.IO) {
        if (imageSource.isBlank()) return@withContext 0.5f

        try {
            kotlinx.coroutines.withTimeout(3500L) {
                var bitmap: Bitmap? = null

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

                if (bitmap == null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(imageSource)
                            .size(720, 720)
                            .allowHardware(false)
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
     * Index-driven favorite import. index.json is the source of truth:
     * read it, keep each entry whose image file exists (loaded with the
     * stored horizontalOffset), drop entries whose file is missing from
     * both the index and the DB. No thumbnails, no optimization, no
     * face detection on import.
     */
    suspend fun syncAndLoadFromSelectedFolder(folderUriString: String) = withContext(Dispatchers.IO) {
        if (folderUriString.isBlank()) return@withContext

        try {
            folderMutex.withLock {
                val folderUri = Uri.parse(folderUriString)
                val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withLock
                if (!rootDoc.canRead()) return@withLock

                if (rootDoc.canWrite() && rootDoc.findFile(".nomedia") == null) {
                    try {
                        rootDoc.createFile("application/octet-stream", ".nomedia")
                    } catch (e: Exception) {
                    }
                }

                // 1. Read index.json (source of truth).
                val indexFile = rootDoc.findFile("index.json")
                val indexEntries = mutableListOf<JSONObject>()
                if (indexFile != null && indexFile.exists()) {
                    try {
                        val jsonString = context.contentResolver.openInputStream(indexFile.uri)?.use { stream ->
                            stream.bufferedReader().use { it.readText() }
                        }
                        if (!jsonString.isNullOrBlank()) {
                            val rootJson = JSONObject(jsonString)
                            // Portable settings travel with the library.
                            // API key is intentionally NOT stored/loaded here:
                            // the folder is user-shared/cloud-synced plaintext,
                            // while the key is a secret kept in app DataStore.
                            applyIndexMetadata(rootJson)
                            val array = rootJson.optJSONArray("wallpapers")
                            if (array != null) {
                                for (i in 0 until array.length()) {
                                    indexEntries.add(array.getJSONObject(i))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WallpaperRepo", "Error parsing existing index.json", e)
                    }
                }

                // 2. Map local files for existence checks.
                val fileMap = buildLocalFileMap(rootDoc)

                // 3. Build surviving list fully in memory, then one DB
                // transaction. Per-row inserts/deletes each emit the Room
                // Flow (recompose + image loads); batching means one
                // emission no matter the library size.
                val currentId = try { preferences.currentWallpaperFlow.first().id } catch (e: Exception) { "" }
                val existingById = favoriteDao.getAllFavoritesDirect().associateBy { it.id }
                val toUpsert = mutableListOf<FavoriteWallpaper>()
                val offsetPuts = mutableMapOf<String, Float>()
                val survivingIds = mutableSetOf<String>()
                var removedAny = false
                for (entry in indexEntries) {
                    val rawId = entry.optString("id")
                    if (rawId.isBlank()) {
                        removedAny = true
                        continue
                    }
                    val id = extractWallhavenId(rawId)
                    val local = fileMap[id]
                    if (local == null) {
                        removedAny = true
                        continue
                    }
                    survivingIds.add(id)

                    val rawOffset = if (entry.has("horizontalOffset")) {
                        entry.optDouble("horizontalOffset", Double.NaN)
                    } else {
                        Double.NaN
                    }
                    val offset = if (!rawOffset.isNaN() && rawOffset in 0.0..1.0) {
                        rawOffset.toFloat()
                    } else {
                        0.5f
                    }
                    offsetPuts[id] = offset

                    val localUri = local.first.uri
                    val url = entry.optString("url").ifBlank { localUri.toString() }
                    val candidate = FavoriteWallpaper(
                        id = id,
                        url = url,
                        thumbUrl = localUri.toString(),
                        purity = entry.optString("purity").ifBlank { local.second },
                        category = entry.optString("category").ifBlank { "all" },
                        horizontalOffset = offset,
                        addedTimestamp = entry.optLong("timestamp", System.currentTimeMillis()),
                        localFilePath = localUri.toString()
                    )
                    // Skip unchanged rows: re-inserting identical rows would
                    // still invalidate the Flow and thrash the grid.
                    val existing = existingById[id]
                    if (existing == null ||
                        existing.url != candidate.url ||
                        existing.purity != candidate.purity ||
                        existing.category != candidate.category ||
                        existing.horizontalOffset != candidate.horizontalOffset ||
                        existing.addedTimestamp != candidate.addedTimestamp ||
                        existing.localFilePath != candidate.localFilePath
                    ) {
                        toUpsert.add(candidate)
                    }
                }

                // DB mirrors the index: drop rows not in the surviving set.
                val toDelete = existingById.keys.filter { it !in survivingIds }
                if (toDelete.isNotEmpty()) removedAny = true

                favoriteDao.syncFavorites(toUpsert, toDelete)

                // Single SharedPreferences flush for the whole import
                // instead of one edit().apply() per image.
                if (offsetPuts.isNotEmpty() || toDelete.isNotEmpty()) {
                    memoryOffsetCache.putAll(offsetPuts)
                    toDelete.forEach { memoryOffsetCache.remove(it) }
                    positioningPrefs.edit().apply {
                        offsetPuts.forEach { (id, offset) -> putFloat(id, offset) }
                        toDelete.forEach { remove(it) }
                    }.apply()
                }

                // 4. Persist removals back to the index.
                if (removedAny) {
                    updateFolderMetadataIndex(rootDoc)
                }
                // Only wake the live wallpaper when its image was actually
                // removed; a pure import doesn't change the current image,
                // and the reload would decode a full image during grid load.
                if (currentId.isNotBlank() && currentId in toDelete) {
                    notifyWallpaperChanged()
                }
            }
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

            if (rootDoc.findFile(".nomedia") == null) {
                try {
                    rootDoc.createFile("application/octet-stream", ".nomedia")
                } catch (e: Exception) {
                }
            }

            val subfolderName = if (fav.purity == "sfw") "sfw" else "nsfw"
            var subDir = rootDoc.findFile(subfolderName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = rootDoc.createDirectory(subfolderName) ?: return@withContext
            }

            if (subDir.findFile(".nomedia") == null) {
                try {
                    subDir.createFile("application/octet-stream", ".nomedia")
                } catch (e: Exception) {
                }
            }

            var targetFile = subDir.listFiles().firstOrNull { f ->
                if (f.isDirectory) false
                else {
                    val fName = f.name ?: return@firstOrNull false
                    imageIdFromFileName(fName) == fav.id
                }
            }
            if (targetFile == null) {
                val isPng = fav.url.endsWith(".png", ignoreCase = true)
                val extension = if (isPng) ".png" else ".jpg"
                val mimeType = if (isPng) "image/png" else "image/jpeg"
                val fileName = "${fav.id}$extension"
                targetFile = subDir.createFile(mimeType, fileName)
                if (targetFile != null) {
                    val request = Request.Builder().url(fav.url).build()
                    val response = NetworkClient.okHttpClient.newCall(request).execute()
                    response.body?.byteStream()?.use { inputStream ->
                        context.contentResolver.openOutputStream(targetFile.uri)?.use { outStream ->
                            inputStream.copyTo(outStream)
                        }
                    }
                }
            }

            if (targetFile != null) {
                try {
                    favoriteDao.updateLocalFilePath(fav.id, targetFile.uri.toString())
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error updating local path for ${fav.id}", e)
                }
            }

            folderMutex.withLock {
                updateFolderMetadataIndex(rootDoc)
            }

            // Optimize the newly added file as part of saving so every new
            // favorite is stored as an optimized WebP before this returns
            // (never a bulk pass).
            val savedFile = targetFile
            if (savedFile != null) {
                optimizeSingleWallpaperFile(savedFile, fav.id, subDir)
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error saving wallpaper to user folder", e)
        }
    }

    /**
     * Applies portable top-level index fields (schedule interval + SFW/NSFW
     * filters) to preferences. Missing/invalid fields keep current prefs.
     * API key / schedule-enabled are deliberately ignored: the key is a
     * secret that must never sit in a shared/cloud folder, and enabled is
     * device-specific state.
     */
    private suspend fun applyIndexMetadata(rootJson: JSONObject) {
        try {
            if (rootJson.has("scheduleIntervalMinutes")) {
                val interval = rootJson.optInt("scheduleIntervalMinutes", -1)
                if (interval in 1..1440) {
                    preferences.setIntervalMinutes(interval)
                }
            }
            val sfwJson = rootJson.optJSONObject("sfwFilters")
            if (sfwJson != null) {
                preferences.updateSfwFilters(
                    grayscale = sfwJson.optDouble("grayscale", 0.0).toFloat().coerceIn(0f, 1f),
                    blur = sfwJson.optDouble("blur", 0.0).toFloat().coerceIn(0f, 0.1f),
                    brightness = sfwJson.optDouble("brightness", 1.0).toFloat().coerceIn(0f, 1f),
                    vignette = sfwJson.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f)
                )
            }
            val nsfwJson = rootJson.optJSONObject("nsfwFilters")
            if (nsfwJson != null) {
                preferences.updateNsfwFilters(
                    grayscale = nsfwJson.optDouble("grayscale", 0.0).toFloat().coerceIn(0f, 1f),
                    blur = nsfwJson.optDouble("blur", 0.0).toFloat().coerceIn(0f, 0.1f),
                    brightness = nsfwJson.optDouble("brightness", 1.0).toFloat().coerceIn(0f, 1f),
                    vignette = nsfwJson.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f)
                )
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error applying index metadata", e)
        }
    }

    /**
     * Rewrites index.json from current DB + preferences (used after
     * settings-only changes such as filter/interval edits, which don't
     * otherwise trigger an index write). No-op if no folder is selected.
     * Skips the write if folderMutex is busy — the ongoing sync already
     * rewrites with the latest prefs.
     */
    suspend fun refreshFolderIndex() = withContext(Dispatchers.IO) {
        val folderUriString = try {
            preferences.localFolderUriFlow.first()
        } catch (e: Exception) {
            return@withContext
        }
        if (folderUriString.isBlank()) return@withContext
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriString)) ?: return@withContext
            if (!rootDoc.exists() || !rootDoc.canWrite()) return@withContext
            if (folderMutex.tryLock()) {
                try {
                    updateFolderMetadataIndex(rootDoc)
                } finally {
                    folderMutex.unlock()
                }
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error refreshing index.json", e)
        }
    }

    private suspend fun updateFolderMetadataIndex(rootDoc: DocumentFile) {
        try {
            var indexFile = rootDoc.findFile("index.json")
            if (indexFile == null) {
                indexFile = rootDoc.createFile("application/json", "index.json") ?: return
            }

            val allFavs = favoriteDao.getAllFavoritesDirect()
            val optimizedIds = preferences.optimizedWallpaperIdsFlow.first()
            val intervalMinutes = preferences.intervalMinutesFlow.first()
            val sfwFilters = preferences.sfwFilterFlow.first()
            val nsfwFilters = preferences.nsfwFilterFlow.first()
            val jsonArray = JSONArray()
            for (fav in allFavs) {
                val itemObj = JSONObject().apply {
                    put("id", fav.id)
                    put("purity", fav.purity)
                    put("category", fav.category)
                    put("horizontalOffset", fav.horizontalOffset.toDouble())
                    put("url", fav.url)
                    put("timestamp", fav.addedTimestamp)
                    put("optimized", optimizedIds.contains(fav.id))
                }
                jsonArray.put(itemObj)
            }

            val rootJson = JSONObject().apply {
                put("updatedAt", System.currentTimeMillis())
                put("count", jsonArray.length())
                put("scheduleIntervalMinutes", intervalMinutes)
                put("sfwFilters", JSONObject().apply {
                    put("grayscale", sfwFilters.grayscale.toDouble())
                    put("blur", sfwFilters.blur.toDouble())
                    put("brightness", sfwFilters.brightness.toDouble())
                    put("vignette", sfwFilters.vignette.toDouble())
                })
                put("nsfwFilters", JSONObject().apply {
                    put("grayscale", nsfwFilters.grayscale.toDouble())
                    put("blur", nsfwFilters.blur.toDouble())
                    put("brightness", nsfwFilters.brightness.toDouble())
                    put("vignette", nsfwFilters.vignette.toDouble())
                })
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

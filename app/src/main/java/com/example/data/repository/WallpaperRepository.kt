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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
            return buildWallhavenQuery(query.orEmpty().split(',', ' '))
        }

        /** Builds the Wallhaven `q` query from a list of tags. See [buildWallhavenQuery]. */
        fun buildWallhavenQuery(terms: List<String>): String? {
            val cleaned = terms
                .flatMap { it.split(',', ' ') }
                .map { it.trim().trimStart('+', '-') }
                .map { it.lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            return if (cleaned.isEmpty()) null else cleaned.joinToString(" ")
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

            // Generate high-performance internal thumbnail in app internal storage
            val internalThumb = generateInternalThumbnail(id, sourceUri = null, sourceUrl = thumbUrl.ifBlank { url })
            val fav = FavoriteWallpaper(
                id = id,
                url = url,
                thumbUrl = internalThumb.ifBlank { thumbUrl },
                purity = purity,
                category = category,
                horizontalOffset = cropOffset,
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

        // 5. If this deleted item is currently active on screen, immediately advance to the next wallpaper
        val current = preferences.currentWallpaperFlow.first()
        if (current.id == id) {
            try {
                fetchAndApplyNextWallpaper()
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error advancing wallpaper after deleting current", e)
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
        val finalOffset = getOrDeterminePositionOffset(
            fav.id,
            fav.localFilePath ?: fav.thumbUrl.ifBlank { fav.url }
        )
        if (fav.horizontalOffset !in 0f..1f) {
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
            val candidate = if (favorites.size > 1) {
                favorites.filter { it.id != current.id }.randomOrNull() ?: favorites.random()
            } else {
                favorites.first()
            }

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

        val wallhavenQuery = buildWallhavenQuery(query)

        try {
            val response = kotlinx.coroutines.withTimeout(20000L) {
                apiService.searchWallpapers(
                    query = wallhavenQuery,
                    categories = categoriesCode,
                    purity = purityCode,
                    sorting = "relevance",
                    order = "desc",
                    page = page,
                    apiKey = apiKey
                )
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
            kotlinx.coroutines.withTimeout(20000L) {
                apiService.searchWallpapers(
                    query = wallhavenQuery,
                    categories = categoriesCode,
                    purity = purityCode,
                    sorting = "relevance",
                    order = "desc",
                    apiKey = apiKey
                )
            }
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
            val folderUri = Uri.parse(folderUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            if (!rootDoc.canRead()) return@withContext

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

            // 4. Process each scanned file (with its index metadata when present)
            for (id in discoveredFiles.keys) {
                val indexJson = indexedItemsMap[id]
                val localFileInfo = discoveredFiles[id]

                val purity = indexJson?.optString("purity")
                    ?: localFileInfo?.second
                    ?: "sfw"
                val category = indexJson?.optString("category") ?: "all"

                // Check if existing positioning data was provided
                val hasExistingOffset = indexJson != null && indexJson.has("horizontalOffset")
                val horizontalOffset = if (hasExistingOffset) {
                    val off = indexJson!!.optDouble("horizontalOffset", 0.5).toFloat().coerceIn(0f, 1f)
                    setCachedOffset(id, off)
                    off
                } else {
                    -1f // Unset: face detection will run upon application
                }

                val timestamp = indexJson?.optLong("timestamp", System.currentTimeMillis()) ?: System.currentTimeMillis()
                val url = indexJson?.optString("url")
                    ?: localFileInfo?.first?.uri?.toString()
                    ?: "https://w.wallhaven.cc/full/${id.take(2)}/wallhaven-$id.jpg"

                val localFileUri = localFileInfo?.first?.uri

                // Generate high-performance thumbnail stored in internal storage
                val internalThumb = generateInternalThumbnail(
                    id = id,
                    sourceUri = localFileUri,
                    sourceUrl = url
                )

                val fav = FavoriteWallpaper(
                    id = id,
                    url = url,
                    thumbUrl = internalThumb.ifBlank { localFileUri?.toString() ?: url },
                    purity = purity,
                    category = category,
                    horizontalOffset = horizontalOffset,
                    addedTimestamp = timestamp,
                    localFilePath = localFileUri?.toString()
                )

                favoriteDao.insertFavorite(fav)
            }

            // 5. Update/refresh index.json in the user folder so it reflects all favorites
            updateFolderMetadataIndex(rootDoc)
            notifyWallpaperChanged()
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

            // Determine extension (.png or .jpg) from url
            val isPng = fav.url.endsWith(".png", ignoreCase = true)
            val extension = if (isPng) ".png" else ".jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val fileName = "${fav.id}$extension"

            var targetFile = subDir.findFile(fileName)
            if (targetFile == null) {
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
            val jsonArray = JSONArray()
            for (id in allFavs) {
                val fav = favoriteDao.getFavoriteById(id) ?: continue
                val itemObj = JSONObject().apply {
                    put("id", fav.id)
                    put("purity", fav.purity)
                    put("category", fav.category)
                    if (fav.horizontalOffset in 0f..1f) {
                        put("horizontalOffset", fav.horizontalOffset)
                    }
                    put("url", fav.url)
                    put("timestamp", fav.addedTimestamp)
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

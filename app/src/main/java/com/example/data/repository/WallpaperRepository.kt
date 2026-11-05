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
        cropOffset: Float
    ): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext false
        val exists = favoriteDao.isFavoriteDirect(id)
        if (exists) {
            favoriteDao.deleteById(id)
            false
        } else {
            // Generate high-performance internal thumbnail in app internal storage
            val internalThumb = generateInternalThumbnail(id, sourceUri = null, sourceUrl = thumbUrl.ifBlank { url })
            val fav = FavoriteWallpaper(
                id = id,
                url = url,
                thumbUrl = internalThumb.ifBlank { thumbUrl },
                purity = purity,
                category = category,
                horizontalOffset = cropOffset,
                addedTimestamp = System.currentTimeMillis()
            )
            favoriteDao.insertFavorite(fav)
            // Save to local user folder if configured
            saveWallpaperToLocalFolder(fav)
            true
        }
    }

    suspend fun deleteFavorite(id: String) = withContext(Dispatchers.IO) {
        favoriteDao.deleteById(id)
    }

    suspend fun updateCropOffset(id: String, offset: Float) = withContext(Dispatchers.IO) {
        val clamped = offset.coerceIn(0f, 1f)
        preferences.updateCurrentWallpaperOffset(clamped)
        val isFav = favoriteDao.isFavoriteDirect(id)
        if (isFav) {
            favoriteDao.updateCropOffset(id, clamped)
        } else {
            val current = preferences.currentWallpaperFlow.first()
            if (current.id == id && current.id.isNotBlank()) {
                val internalThumb = generateInternalThumbnail(id, sourceUri = null, sourceUrl = current.thumbUrl.ifBlank { current.url })
                val fav = FavoriteWallpaper(
                    id = current.id,
                    url = current.url,
                    thumbUrl = internalThumb.ifBlank { current.thumbUrl },
                    purity = current.purity,
                    category = current.category,
                    horizontalOffset = clamped,
                    addedTimestamp = System.currentTimeMillis()
                )
                favoriteDao.insertFavorite(fav)
                saveWallpaperToLocalFolder(fav)
            }
        }
    }

    /**
     * Applies a favorite as current wallpaper.
     * If the favorite was imported from a folder without existing positioning data (offset < 0f),
     * runs face detection and applies positioning before setting the image to prevent jank.
     * Otherwise preserves existing user positioning data.
     */
    suspend fun applyFavorite(fav: FavoriteWallpaper) = withContext(Dispatchers.IO) {
        val finalOffset = if (fav.horizontalOffset in 0f..1f) {
            // Preserves existing positioning data
            fav.horizontalOffset
        } else {
            // Favorite applied from folder without existing positioning data:
            // Calculate face/focal offset and update before setting image to prevent jank
            val detected = detectFaceOffset(fav.localFilePath ?: fav.url)
            favoriteDao.updateCropOffset(fav.id, detected)
            detected
        }

        preferences.setCurrentWallpaper(
            id = fav.id,
            url = fav.url,
            thumbUrl = fav.thumbUrl,
            purity = fav.purity,
            category = fav.category,
            offset = finalOffset
        )
        notifyWallpaperChanged()
    }

    /**
     * Fetches and applies the next wallpaper.
     * 1) When a new image is loaded from Wallhaven: face detection is performed and positioning
     *    is applied before setting the image to prevent visual jank.
     * 2) If no tag is selected, shuffles saved favorites (preserving existing positioning, or
     *    detecting if missing).
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
                    val finalOffset = if (randomFav.horizontalOffset in 0f..1f) {
                        randomFav.horizontalOffset
                    } else {
                        val detected = detectFaceOffset(randomFav.localFilePath ?: randomFav.url)
                        favoriteDao.updateCropOffset(randomFav.id, detected)
                        detected
                    }
                    candidateWallpaper = Wallpaper(
                        id = randomFav.id,
                        fullUrl = randomFav.url,
                        thumbUrl = randomFav.thumbUrl,
                        purity = randomFav.purity,
                        category = randomFav.category,
                        resolution = randomFav.resolution,
                        horizontalOffset = finalOffset,
                        isFavorite = true
                    )
                }
            }

            if (candidateWallpaper == null) {
                // Query Wallhaven API (New image!)
                val fetched = fetchFromWallhaven(isNsfw, tags, existingIds)
                if (fetched != null) {
                    // 1) When a new image is loaded: detect face and position to the right side of the screen
                    // Apply positioning BEFORE setting the image to prevent jank
                    val smartOffset = detectFaceOffset(fetched.fullUrl)
                    candidateWallpaper = fetched.copy(horizontalOffset = smartOffset)
                }
            }

            if (candidateWallpaper != null) {
                // Update current wallpaper state with final pre-calculated offset
                preferences.setCurrentWallpaper(
                    id = candidateWallpaper.id,
                    url = candidateWallpaper.fullUrl,
                    thumbUrl = candidateWallpaper.thumbUrl,
                    purity = candidateWallpaper.purity,
                    category = candidateWallpaper.category,
                    offset = candidateWallpaper.horizontalOffset
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

        val purityCode = if (!isNsfw) {
            "100"
        } else {
            when (preferences.nsfwPurityFlow.first()) {
                "sketchy" -> "010"
                "nsfw" -> "001"
                else -> "011"
            }
        }

        // Search query with inclusive tags
        val queryParam = if (tags.isNotEmpty()) {
            tags.joinToString(" ") { "+${it.trim()}" }
        } else {
            null
        }

        var response = apiService.searchWallpapers(
            query = queryParam,
            categories = categoriesCode,
            purity = purityCode,
            sorting = "random",
            apiKey = apiKey
        )

        // If specific tag search returned 0 items, fallback to general search so wallpapers always load
        if (response.data.isEmpty() && queryParam != null) {
            response = apiService.searchWallpapers(
                query = null,
                categories = categoriesCode,
                purity = purityCode,
                sorting = "random",
                apiKey = apiKey
            )
        }

        // Filter out duplicate IDs if possible
        val items = response.data.filter { it.id !in existingIds }
        val item = items.firstOrNull() ?: response.data.firstOrNull() ?: return null

        val fullUrl = item.path.ifBlank { item.url }
        val thumb = item.thumbs?.large ?: item.thumbs?.original ?: item.thumbs?.small ?: fullUrl

        return Wallpaper(
            id = item.id,
            fullUrl = fullUrl,
            thumbUrl = thumb,
            purity = item.purity,
            category = item.category,
            resolution = item.resolution,
            horizontalOffset = 0.5f,
            isFavorite = false
        )
    }

    /**
     * Runs ML Kit face detection on the image to position the focus to the right side of the screen
     * within the device's aspect ratio.
     */
    suspend fun detectFaceOffset(imageSource: String): Float = withContext(Dispatchers.IO) {
        if (imageSource.isBlank()) return@withContext 0.5f

        try {
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
                    while (maxDim / (sample * 2) >= 960) {
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
                        .size(1024, 1024)
                        .allowHardware(false) // Must be software bitmap for ML Kit
                        .build()
                    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                    bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                } catch (e: Exception) {
                    Log.e("WallpaperRepo", "Error loading remote bitmap for face detection", e)
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

                    val targetSize = 480
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
                    .size(480, 800)
                    .allowHardware(false)
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }

            val finalBitmap = bitmap
            if (finalBitmap != null) {
                FileOutputStream(thumbFile).use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                    } else {
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
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

            // 3. Process each item:
            // Combine items from index.json and scanned files
            val allIds = (indexedItemsMap.keys + discoveredFiles.keys).distinct()

            for (id in allIds) {
                val indexJson = indexedItemsMap[id]
                val localFileInfo = discoveredFiles[id]

                val purity = indexJson?.optString("purity")
                    ?: localFileInfo?.second
                    ?: "sfw"
                val category = indexJson?.optString("category") ?: "all"

                // Check if existing positioning data was provided
                val hasExistingOffset = indexJson != null && indexJson.has("horizontalOffset")
                val horizontalOffset = if (hasExistingOffset) {
                    indexJson!!.optDouble("horizontalOffset", 0.5).toFloat().coerceIn(0f, 1f)
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

            // 4. Update/refresh index.json in the user folder so it reflects all favorites
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

            val subfolderName = if (fav.purity == "sfw") "sfw" else "nsfw"
            var subDir = rootDoc.findFile(subfolderName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = rootDoc.createDirectory(subfolderName) ?: return@withContext
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

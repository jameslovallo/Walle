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
            val fav = FavoriteWallpaper(
                id = id,
                url = url,
                thumbUrl = thumbUrl,
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
            // "replacing the image that is already saved in favorites, or adding it if it is not a favorite already."
            val current = preferences.currentWallpaperFlow.first()
            if (current.id == id && current.id.isNotBlank()) {
                val fav = FavoriteWallpaper(
                    id = current.id,
                    url = current.url,
                    thumbUrl = current.thumbUrl,
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
     * Fetches and applies the next wallpaper.
     * When tags are active, queries Wallhaven with inclusive tags.
     * If no tag is selected, shuffles saved favorites.
     */
    suspend fun fetchAndApplyNextWallpaper(): Result<Wallpaper> = withContext(Dispatchers.IO) {
        try {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val tags = if (isNsfw) preferences.nsfwTagsFlow.first() else preferences.sfwTagsFlow.first()
            val existingIds = favoriteDao.getAllFavoriteIds().toSet()

            var candidateWallpaper: Wallpaper? = null

            if (tags.isEmpty()) {
                // If no tag is selected, shuffled photos saved from the favorites
                val purities = if (isNsfw) listOf("sketchy", "nsfw") else listOf("sfw")
                val randomFav = favoriteDao.getRandomFavorite(purities)
                if (randomFav != null) {
                    candidateWallpaper = Wallpaper(
                        id = randomFav.id,
                        fullUrl = randomFav.url,
                        thumbUrl = randomFav.thumbUrl,
                        purity = randomFav.purity,
                        category = randomFav.category,
                        resolution = randomFav.resolution,
                        horizontalOffset = randomFav.horizontalOffset,
                        isFavorite = true
                    )
                }
            }

            if (candidateWallpaper == null) {
                // Query Wallhaven API
                candidateWallpaper = fetchFromWallhaven(isNsfw, tags, existingIds)
            }

            if (candidateWallpaper != null) {
                val finalWallpaper = candidateWallpaper

                // Update current wallpaper state
                preferences.setCurrentWallpaper(
                    id = finalWallpaper.id,
                    url = finalWallpaper.fullUrl,
                    thumbUrl = finalWallpaper.thumbUrl,
                    purity = finalWallpaper.purity,
                    category = finalWallpaper.category,
                    offset = finalWallpaper.horizontalOffset
                )

                // Broadcast update event to Live Wallpaper
                notifyWallpaperChanged()

                Result.success(finalWallpaper)
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

        // Categories: "all" -> 111, "anime" -> 010, "people" -> 001
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

        // Purity:
        // SFW mode: 100
        // NSFW mode: sketchy (010), nsfw (001), or both (011)
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

            if (subDir.findFile(fileName) == null) {
                val newFile = subDir.createFile(mimeType, fileName) ?: return@withContext
                // Stream the original untouched full-resolution file from wallhaven
                val request = Request.Builder().url(fav.url).build()
                val response = NetworkClient.okHttpClient.newCall(request).execute()
                response.body?.byteStream()?.use { inputStream ->
                    context.contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
                        inputStream.copyTo(outStream)
                    }
                }
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
                    put("horizontalOffset", fav.horizontalOffset)
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

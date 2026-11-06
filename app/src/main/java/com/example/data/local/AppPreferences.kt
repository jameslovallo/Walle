package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ModeFilterSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "walle_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        val KEY_IS_NSFW_MODE = booleanPreferencesKey("is_nsfw_mode")
        val KEY_API_KEY = stringPreferencesKey("wallhaven_api_key")
        val KEY_INTERVAL_MINUTES = intPreferencesKey("interval_minutes")

        // SFW filters: grayscale, blur, brightness (opacity over black), vignette
        val KEY_SFW_GRAYSCALE = floatPreferencesKey("sfw_grayscale")
        val KEY_SFW_BLUR = floatPreferencesKey("sfw_blur")
        val KEY_SFW_BRIGHTNESS = floatPreferencesKey("sfw_brightness")
        val KEY_SFW_VIGNETTE = floatPreferencesKey("sfw_vignette")

        // NSFW filters: grayscale, blur, brightness (opacity over black), vignette
        val KEY_NSFW_GRAYSCALE = floatPreferencesKey("nsfw_grayscale")
        val KEY_NSFW_BLUR = floatPreferencesKey("nsfw_blur")
        val KEY_NSFW_BRIGHTNESS = floatPreferencesKey("nsfw_brightness")
        val KEY_NSFW_VIGNETTE = floatPreferencesKey("nsfw_vignette")

        // Tags
        val KEY_SAVED_SFW_TAGS = stringSetPreferencesKey("saved_sfw_tags")
        val KEY_ACTIVE_SFW_TAGS = stringSetPreferencesKey("active_sfw_tags")
        val KEY_SAVED_NSFW_TAGS = stringSetPreferencesKey("saved_nsfw_tags")
        val KEY_ACTIVE_NSFW_TAGS = stringSetPreferencesKey("active_nsfw_tags")

        // NSFW Purity: "both", "sketchy", "nsfw"
        val KEY_NSFW_PURITY = stringPreferencesKey("nsfw_purity")
        // Categories: "all", "anime", "people"
        val KEY_SFW_CATEGORY = stringPreferencesKey("sfw_category")
        val KEY_NSFW_CATEGORY = stringPreferencesKey("nsfw_category")

        val KEY_LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")

        // Current Wallpaper state
        val KEY_CURRENT_ID = stringPreferencesKey("current_id")
        val KEY_CURRENT_URL = stringPreferencesKey("current_url")
        val KEY_CURRENT_THUMB = stringPreferencesKey("current_thumb")
        val KEY_CURRENT_PURITY = stringPreferencesKey("current_purity")
        val KEY_CURRENT_CATEGORY = stringPreferencesKey("current_category")
        val KEY_CURRENT_OFFSET = floatPreferencesKey("current_offset")
        val KEY_CURRENT_TAGS = stringPreferencesKey("current_tags")

        val KEY_DELETED_WALLPAPER_IDS = stringSetPreferencesKey("deleted_wallpaper_ids")
        val KEY_IS_SCHEDULE_ENABLED = booleanPreferencesKey("is_schedule_enabled")
    }

    val isScheduleEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_IS_SCHEDULE_ENABLED] ?: true
    }

    val deletedWallpaperIdsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_DELETED_WALLPAPER_IDS] ?: emptySet()
    }

    val isNsfwModeFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_IS_NSFW_MODE] ?: false
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_API_KEY] ?: ""
    }

    val intervalMinutesFlow: Flow<Int> = context.dataStore.data.map {
        it[KEY_INTERVAL_MINUTES] ?: 60
    }

    val sfwFilterFlow: Flow<ModeFilterSettings> = context.dataStore.data.map {
        ModeFilterSettings(
            grayscale = it[KEY_SFW_GRAYSCALE] ?: 0f,
            blur = it[KEY_SFW_BLUR] ?: 0f,
            brightness = it[KEY_SFW_BRIGHTNESS] ?: 1.0f,
            vignette = it[KEY_SFW_VIGNETTE] ?: 0f
        )
    }

    val nsfwFilterFlow: Flow<ModeFilterSettings> = context.dataStore.data.map {
        ModeFilterSettings(
            grayscale = it[KEY_NSFW_GRAYSCALE] ?: 0f,
            blur = it[KEY_NSFW_BLUR] ?: 0f,
            brightness = it[KEY_NSFW_BRIGHTNESS] ?: 1.0f,
            vignette = it[KEY_NSFW_VIGNETTE] ?: 0f
        )
    }

    val savedSfwTagsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_SAVED_SFW_TAGS] ?: setOf("nature")
    }

    val activeSfwTagsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_ACTIVE_SFW_TAGS] ?: setOf("nature")
    }

    val savedNsfwTagsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_SAVED_NSFW_TAGS] ?: setOf("model")
    }

    val activeNsfwTagsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_ACTIVE_NSFW_TAGS] ?: setOf("model")
    }

    val sfwTagsFlow: Flow<Set<String>> get() = activeSfwTagsFlow
    val nsfwTagsFlow: Flow<Set<String>> get() = activeNsfwTagsFlow

    val nsfwPurityFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_NSFW_PURITY] ?: "both" // "both", "sketchy", "nsfw"
    }

    val sfwCategoryFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_SFW_CATEGORY] ?: "all"
    }

    val nsfwCategoryFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_NSFW_CATEGORY] ?: "people" // Defaults to people for nsfw
    }

    val localFolderUriFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_LOCAL_FOLDER_URI] ?: ""
    }

    val currentWallpaperFlow: Flow<CurrentWallpaperInfo> = context.dataStore.data.map {
        val rawTags = it[KEY_CURRENT_TAGS] ?: ""
        val tagsList = if (rawTags.isNotBlank()) {
            rawTags.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() }
        } else {
            emptyList()
        }
        CurrentWallpaperInfo(
            id = it[KEY_CURRENT_ID] ?: "",
            url = it[KEY_CURRENT_URL] ?: "",
            thumbUrl = it[KEY_CURRENT_THUMB] ?: "",
            purity = it[KEY_CURRENT_PURITY] ?: "sfw",
            category = it[KEY_CURRENT_CATEGORY] ?: "general",
            horizontalOffset = it[KEY_CURRENT_OFFSET] ?: 0.5f,
            tags = tagsList
        )
    }

    suspend fun setNsfwMode(isNsfw: Boolean) {
        context.dataStore.edit { it[KEY_IS_NSFW_MODE] = isNsfw }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[KEY_API_KEY] = key }
    }

    suspend fun setIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_INTERVAL_MINUTES] = minutes }
    }

    suspend fun updateSfwFilters(grayscale: Float, blur: Float, brightness: Float, vignette: Float) {
        context.dataStore.edit {
            it[KEY_SFW_GRAYSCALE] = grayscale
            it[KEY_SFW_BLUR] = blur
            it[KEY_SFW_BRIGHTNESS] = brightness
            it[KEY_SFW_VIGNETTE] = vignette
        }
    }

    suspend fun updateNsfwFilters(grayscale: Float, blur: Float, brightness: Float, vignette: Float) {
        context.dataStore.edit {
            it[KEY_NSFW_GRAYSCALE] = grayscale
            it[KEY_NSFW_BLUR] = blur
            it[KEY_NSFW_BRIGHTNESS] = brightness
            it[KEY_NSFW_VIGNETTE] = vignette
        }
    }

    suspend fun setSavedSfwTags(tags: Set<String>) {
        context.dataStore.edit { it[KEY_SAVED_SFW_TAGS] = tags }
    }

    suspend fun setActiveSfwTags(tags: Set<String>) {
        context.dataStore.edit { it[KEY_ACTIVE_SFW_TAGS] = tags }
    }

    suspend fun setSavedNsfwTags(tags: Set<String>) {
        context.dataStore.edit { it[KEY_SAVED_NSFW_TAGS] = tags }
    }

    suspend fun setActiveNsfwTags(tags: Set<String>) {
        context.dataStore.edit { it[KEY_ACTIVE_NSFW_TAGS] = tags }
    }

    suspend fun setSfwTags(tags: Set<String>) = setActiveSfwTags(tags)
    suspend fun setNsfwTags(tags: Set<String>) = setActiveNsfwTags(tags)

    suspend fun setNsfwPurity(purity: String) {
        context.dataStore.edit { it[KEY_NSFW_PURITY] = purity }
    }

    suspend fun setSfwCategory(cat: String) {
        context.dataStore.edit { it[KEY_SFW_CATEGORY] = cat }
    }

    suspend fun setNsfwCategory(cat: String) {
        context.dataStore.edit { it[KEY_NSFW_CATEGORY] = cat }
    }

    suspend fun setLocalFolderUri(uri: String) {
        context.dataStore.edit { it[KEY_LOCAL_FOLDER_URI] = uri }
    }

    suspend fun setCurrentWallpaper(
        id: String,
        url: String,
        thumbUrl: String,
        purity: String,
        category: String,
        offset: Float,
        tags: List<String> = emptyList()
    ) {
        context.dataStore.edit {
            it[KEY_CURRENT_ID] = id
            it[KEY_CURRENT_URL] = url
            it[KEY_CURRENT_THUMB] = thumbUrl
            it[KEY_CURRENT_PURITY] = purity
            it[KEY_CURRENT_CATEGORY] = category
            it[KEY_CURRENT_OFFSET] = offset
            it[KEY_CURRENT_TAGS] = tags.joinToString(",")
        }
    }

    suspend fun updateCurrentWallpaperOffset(offset: Float) {
        context.dataStore.edit {
            it[KEY_CURRENT_OFFSET] = offset
        }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[KEY_IS_SCHEDULE_ENABLED] = enabled
        }
    }

    suspend fun updateCurrentWallpaperTags(id: String, tags: List<String>) {
        if (id.isBlank() || tags.isEmpty()) return
        context.dataStore.edit {
            if (it[KEY_CURRENT_ID] == id) {
                it[KEY_CURRENT_TAGS] = tags.joinToString(",")
            }
        }
    }

    suspend fun addDeletedWallpaperId(id: String) {
        if (id.isBlank()) return
        context.dataStore.edit {
            val current = it[KEY_DELETED_WALLPAPER_IDS] ?: emptySet()
            it[KEY_DELETED_WALLPAPER_IDS] = current + id
        }
    }

    suspend fun removeDeletedWallpaperId(id: String) {
        context.dataStore.edit {
            val current = it[KEY_DELETED_WALLPAPER_IDS] ?: emptySet()
            it[KEY_DELETED_WALLPAPER_IDS] = current - id
        }
    }
}

data class CurrentWallpaperInfo(
    val id: String = "",
    val url: String = "",
    val thumbUrl: String = "",
    val purity: String = "sfw",
    val category: String = "general",
    val horizontalOffset: Float = 0.5f,
    val tags: List<String> = emptyList()
)

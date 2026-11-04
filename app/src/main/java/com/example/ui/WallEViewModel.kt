package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WallEApplication
import com.example.data.local.CurrentWallpaperInfo
import com.example.data.local.FavoriteWallpaper
import com.example.data.model.ModeFilterSettings
import com.example.worker.WallpaperIntervalWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WallEUIState(
    val isNsfwMode: Boolean = false,
    val currentWallpaper: CurrentWallpaperInfo = CurrentWallpaperInfo(),
    val isCurrentFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val savedSfwTags: Set<String> = emptySet(),
    val activeSfwTags: Set<String> = emptySet(),
    val savedNsfwTags: Set<String> = emptySet(),
    val activeNsfwTags: Set<String> = emptySet(),
    val nsfwPurity: String = "both",
    val sfwCategory: String = "all",
    val nsfwCategory: String = "people",
    val sfwFilters: ModeFilterSettings = ModeFilterSettings(),
    val nsfwFilters: ModeFilterSettings = ModeFilterSettings(),
    val apiKey: String = "",
    val intervalMinutes: Int = 60,
    val localFolderUri: String = ""
)

class WallEViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WallEApplication
    private val repository = app.repository
    private val preferences = app.preferences

    val isNsfwMode = repository.isNsfwMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val favorites: StateFlow<List<FavoriteWallpaper>> = isNsfwMode.flatMapLatest { nsfw ->
        repository.getFavorites(nsfw)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(WallEUIState())
    val uiState: StateFlow<WallEUIState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.isNsfwMode.collect { nsfw -> _uiState.value = _uiState.value.copy(isNsfwMode = nsfw) }
        }
        viewModelScope.launch {
            repository.currentWallpaper.collect { current -> _uiState.value = _uiState.value.copy(currentWallpaper = current) }
        }
        viewModelScope.launch {
            repository.savedSfwTags.collect { tags -> _uiState.value = _uiState.value.copy(savedSfwTags = tags) }
        }
        viewModelScope.launch {
            repository.activeSfwTags.collect { tags -> _uiState.value = _uiState.value.copy(activeSfwTags = tags) }
        }
        viewModelScope.launch {
            repository.savedNsfwTags.collect { tags -> _uiState.value = _uiState.value.copy(savedNsfwTags = tags) }
        }
        viewModelScope.launch {
            repository.activeNsfwTags.collect { tags -> _uiState.value = _uiState.value.copy(activeNsfwTags = tags) }
        }
        viewModelScope.launch {
            repository.nsfwPurity.collect { purity -> _uiState.value = _uiState.value.copy(nsfwPurity = purity) }
        }
        viewModelScope.launch {
            repository.sfwCategory.collect { cat -> _uiState.value = _uiState.value.copy(sfwCategory = cat) }
        }
        viewModelScope.launch {
            repository.nsfwCategory.collect { cat -> _uiState.value = _uiState.value.copy(nsfwCategory = cat) }
        }
        viewModelScope.launch {
            repository.sfwFilters.collect { filters -> _uiState.value = _uiState.value.copy(sfwFilters = filters) }
        }
        viewModelScope.launch {
            repository.nsfwFilters.collect { filters -> _uiState.value = _uiState.value.copy(nsfwFilters = filters) }
        }
        viewModelScope.launch {
            repository.apiKey.collect { key -> _uiState.value = _uiState.value.copy(apiKey = key) }
        }
        viewModelScope.launch {
            repository.intervalMinutes.collect { interval -> _uiState.value = _uiState.value.copy(intervalMinutes = interval) }
        }
        viewModelScope.launch {
            repository.localFolderUri.collect { uri -> _uiState.value = _uiState.value.copy(localFolderUri = uri) }
        }

        // Keep current favorite status in sync
        viewModelScope.launch {
            repository.currentWallpaper.flatMapLatest { current ->
                repository.isFavorite(current.id)
            }.collect { isFav ->
                _uiState.value = _uiState.value.copy(isCurrentFavorite = isFav)
            }
        }
    }

    fun toggleMode() {
        val nextMode = !_uiState.value.isNsfwMode
        viewModelScope.launch {
            preferences.setNsfwMode(nextMode)
            fetchNextWallpaper()
        }
    }

    fun fetchNextWallpaper() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.fetchAndApplyNextWallpaper()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun toggleTag(tag: String) {
        viewModelScope.launch {
            val isNsfw = _uiState.value.isNsfwMode
            if (isNsfw) {
                val active = _uiState.value.activeNsfwTags.toMutableSet()
                if (active.contains(tag)) active.remove(tag) else active.add(tag)
                preferences.setActiveNsfwTags(active)
            } else {
                val active = _uiState.value.activeSfwTags.toMutableSet()
                if (active.contains(tag)) active.remove(tag) else active.add(tag)
                preferences.setActiveSfwTags(active)
            }
            fetchNextWallpaper()
        }
    }

    fun addTag(tag: String) {
        val cleanTag = tag.trim().lowercase()
        if (cleanTag.isBlank()) return
        viewModelScope.launch {
            val isNsfw = _uiState.value.isNsfwMode
            if (isNsfw) {
                val saved = _uiState.value.savedNsfwTags.toMutableSet().apply { add(cleanTag) }
                val active = _uiState.value.activeNsfwTags.toMutableSet().apply { add(cleanTag) }
                preferences.setSavedNsfwTags(saved)
                preferences.setActiveNsfwTags(active)
            } else {
                val saved = _uiState.value.savedSfwTags.toMutableSet().apply { add(cleanTag) }
                val active = _uiState.value.activeSfwTags.toMutableSet().apply { add(cleanTag) }
                preferences.setSavedSfwTags(saved)
                preferences.setActiveSfwTags(active)
            }
            fetchNextWallpaper()
        }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch {
            val isNsfw = _uiState.value.isNsfwMode
            var wasActive = false
            if (isNsfw) {
                val saved = _uiState.value.savedNsfwTags.toMutableSet().apply { remove(tag) }
                val active = _uiState.value.activeNsfwTags.toMutableSet().apply { wasActive = remove(tag) }
                preferences.setSavedNsfwTags(saved)
                preferences.setActiveNsfwTags(active)
            } else {
                val saved = _uiState.value.savedSfwTags.toMutableSet().apply { remove(tag) }
                val active = _uiState.value.activeSfwTags.toMutableSet().apply { wasActive = remove(tag) }
                preferences.setSavedSfwTags(saved)
                preferences.setActiveSfwTags(active)
            }
            if (wasActive) {
                fetchNextWallpaper()
            }
        }
    }

    fun updateDragOffset(offset: Float) {
        val clamped = offset.coerceIn(0f, 1f)
        val current = _uiState.value.currentWallpaper
        _uiState.value = _uiState.value.copy(
            currentWallpaper = current.copy(horizontalOffset = clamped)
        )
    }

    fun commitDragOffset(offset: Float) {
        val clamped = offset.coerceIn(0f, 1f)
        val current = _uiState.value.currentWallpaper
        if (current.id.isBlank()) return
        viewModelScope.launch {
            repository.updateCropOffset(current.id, clamped)
            repository.notifyWallpaperChanged()
        }
    }

    fun setNsfwPurity(purity: String) {
        viewModelScope.launch {
            preferences.setNsfwPurity(purity)
            fetchNextWallpaper()
        }
    }

    fun setCategory(category: String) {
        viewModelScope.launch {
            if (_uiState.value.isNsfwMode) {
                preferences.setNsfwCategory(category)
            } else {
                preferences.setSfwCategory(category)
            }
            fetchNextWallpaper()
        }
    }

    fun toggleCurrentFavorite() {
        val current = _uiState.value.currentWallpaper
        if (current.id.isBlank()) return
        viewModelScope.launch {
            repository.toggleFavorite(
                id = current.id,
                url = current.url,
                thumbUrl = current.thumbUrl,
                purity = current.purity,
                category = current.category,
                cropOffset = current.horizontalOffset
            )
        }
    }

    fun deleteFavorite(id: String) {
        viewModelScope.launch {
            repository.deleteFavorite(id)
        }
    }

    fun applyFavoriteAsCurrent(fav: FavoriteWallpaper) {
        viewModelScope.launch {
            preferences.setCurrentWallpaper(
                id = fav.id,
                url = fav.url,
                thumbUrl = fav.thumbUrl,
                purity = fav.purity,
                category = fav.category,
                offset = fav.horizontalOffset
            )
            repository.notifyWallpaperChanged()
        }
    }

    fun updateFilters(brightness: Float, blur: Float, vignette: Float, contrast: Float) {
        viewModelScope.launch {
            if (_uiState.value.isNsfwMode) {
                preferences.updateNsfwFilters(brightness, blur, vignette, contrast)
            } else {
                preferences.updateSfwFilters(brightness, blur, vignette, contrast)
            }
            repository.notifyWallpaperChanged()
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            preferences.setApiKey(key)
        }
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        viewModelScope.launch {
            preferences.setIntervalMinutes(minutes)
            WallpaperIntervalWorker.scheduleNext(context, minutes)
        }
    }

    fun setLocalFolderUri(uri: String) {
        viewModelScope.launch {
            preferences.setLocalFolderUri(uri)
        }
    }
}

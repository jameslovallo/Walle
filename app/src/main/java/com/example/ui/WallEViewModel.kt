package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WallEApplication
import com.example.data.local.CurrentWallpaperInfo
import com.example.data.local.FavoriteWallpaper
import com.example.data.model.ModeFilterSettings
import com.example.data.model.Wallpaper
import com.example.worker.WallpaperIntervalWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WallEUIState(
    val isNsfwMode: Boolean = false,
    val currentWallpaper: CurrentWallpaperInfo = CurrentWallpaperInfo(),
    val isCurrentFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val sfwTags: Set<String> = emptySet(),
    val nsfwTags: Set<String> = emptySet(),
    val nsfwPurity: String = "both",
    val sfwCategory: String = "all",
    val nsfwCategory: String = "people",
    val sfwFilters: ModeFilterSettings = ModeFilterSettings(),
    val nsfwFilters: ModeFilterSettings = ModeFilterSettings(),
    val apiKey: String = "",
    val intervalMinutes: Int = 60,
    val localFolderUri: String = "",
    val editingCropWallpaper: WallpaperCropTarget? = null
)

data class WallpaperCropTarget(
    val id: String,
    val url: String,
    val initialOffset: Float,
    val purity: String,
    val category: String,
    val isFromFavorites: Boolean
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
            repository.sfwTags.collect { tags -> _uiState.value = _uiState.value.copy(sfwTags = tags) }
        }
        viewModelScope.launch {
            repository.nsfwTags.collect { tags -> _uiState.value = _uiState.value.copy(nsfwTags = tags) }
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
            // Changing mode automatically fetches/applies matching wallpaper
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
                val current = _uiState.value.nsfwTags.toMutableSet()
                if (current.contains(tag)) current.remove(tag) else current.add(tag)
                preferences.setNsfwTags(current)
            } else {
                val current = _uiState.value.sfwTags.toMutableSet()
                if (current.contains(tag)) current.remove(tag) else current.add(tag)
                preferences.setSfwTags(current)
            }
            // "When the tags are activated or deactivated, a new photo is fetched and applied in real time from the API"
            fetchNextWallpaper()
        }
    }

    fun addCustomTag(tag: String) {
        if (tag.isBlank()) return
        val cleanTag = tag.trim().lowercase()
        viewModelScope.launch {
            val isNsfw = _uiState.value.isNsfwMode
            if (isNsfw) {
                val current = _uiState.value.nsfwTags.toMutableSet()
                current.add(cleanTag)
                preferences.setNsfwTags(current)
            } else {
                val current = _uiState.value.sfwTags.toMutableSet()
                current.add(cleanTag)
                preferences.setSfwTags(current)
            }
            fetchNextWallpaper()
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

    fun openCropForCurrent() {
        val current = _uiState.value.currentWallpaper
        if (current.id.isBlank()) return
        _uiState.value = _uiState.value.copy(
            editingCropWallpaper = WallpaperCropTarget(
                id = current.id,
                url = current.url,
                initialOffset = current.horizontalOffset,
                purity = current.purity,
                category = current.category,
                isFromFavorites = _uiState.value.isCurrentFavorite
            )
        )
    }

    fun openCropForFavorite(fav: FavoriteWallpaper) {
        _uiState.value = _uiState.value.copy(
            editingCropWallpaper = WallpaperCropTarget(
                id = fav.id,
                url = fav.url,
                initialOffset = fav.horizontalOffset,
                purity = fav.purity,
                category = fav.category,
                isFromFavorites = true
            )
        )
    }

    fun closeCrop() {
        _uiState.value = _uiState.value.copy(editingCropWallpaper = null)
    }

    fun saveCropOffset(id: String, offset: Float) {
        viewModelScope.launch {
            repository.updateCropOffset(id, offset)
            repository.notifyWallpaperChanged()
            closeCrop()
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

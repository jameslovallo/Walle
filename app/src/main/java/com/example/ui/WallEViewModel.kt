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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WallEUIState(
    val isNsfwMode: Boolean = false,
    val currentWallpaper: CurrentWallpaperInfo = CurrentWallpaperInfo(),
    val isCurrentFavorite: Boolean = false,
    val isLoading: Boolean = false,
    // Discover Grid & Search State
    val discoverWallpapers: List<Wallpaper> = emptyList(),
    val discoverQuery: String = "nature",
    val discoverCategory: String = "all", // "all", "anime", "people"
    val discoverPurity: String = "both",  // "both", "sketchy", "nsfw"
    val isDiscoverLoading: Boolean = false,
    val isDiscoverLoadingMore: Boolean = false,
    val discoverCurrentPage: Int = 1,
    val discoverLastPage: Int = 1,
    val discoverTotal: Int = 0,
    val discoverHasReachedEnd: Boolean = false,
    val searchGeneration: Int = 0,
    // Legacy tags & mode filters
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
    val localFolderUri: String = "",
    val isScheduleEnabled: Boolean = true,
    val isSlideshowPaused: Boolean = false,
    val isFavoritesLoading: Boolean = false,
    // Library optimization (always on by default: 4K max, WebP)
    val optimizationOptimized: Int = 0,
    val optimizationTotal: Int = 0,
    val isOptimizingLibrary: Boolean = false
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

    private var folderLoadJob: Job? = null

    fun loadFavoritesFromFolder(uri: String) {
        if (uri.isBlank()) return
        folderLoadJob?.cancel()
        _uiState.value = _uiState.value.copy(isFavoritesLoading = true)
        folderLoadJob = viewModelScope.launch {
            try {
                repository.syncAndLoadFromSelectedFolder(uri)
            } finally {
                if (folderLoadJob == kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    _uiState.value = _uiState.value.copy(isFavoritesLoading = false)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            repository.isNsfwMode.collect { nsfw -> _uiState.value = _uiState.value.copy(isNsfwMode = nsfw) }
        }
        viewModelScope.launch {
            repository.currentWallpaper.collect { current ->
                _uiState.value = _uiState.value.copy(
                    currentWallpaper = current,
                    isLoading = if (current.url.isNotBlank()) false else _uiState.value.isLoading
                )
            }
        }
        viewModelScope.launch {
            val current = repository.currentWallpaper.first()
            if (current.url.isBlank()) {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val isNsfw = preferences.isNsfwModeFlow.first()
                val targetTag = if (isNsfw) "model" else "nature"
                repository.fetchAndApplyInitialWallpaper(isNsfw, targetTag)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
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
            repository.localFolderUri.collect { uri ->
                _uiState.value = _uiState.value.copy(localFolderUri = uri)
                if (uri.isNotBlank()) {
                    loadFavoritesFromFolder(uri)
                }
            }
        }

        viewModelScope.launch {
            preferences.isScheduleEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(isScheduleEnabled = enabled)
            }
        }

        viewModelScope.launch {
            preferences.isSlideshowPausedFlow.collect { paused ->
                _uiState.value = _uiState.value.copy(isSlideshowPaused = paused)
            }
        }

        // Keep current favorite status in sync
        viewModelScope.launch {
            repository.currentWallpaper.flatMapLatest { current ->
                repository.isFavorite(current.id)
            }.collect { isFav ->
                _uiState.value = _uiState.value.copy(isCurrentFavorite = isFav)
            }
        }

        // Track when favorites load from database
        viewModelScope.launch {
            favorites.collect {
                _uiState.value = _uiState.value.copy(isFavoritesLoading = false)
            }
        }

        // Initial search for Discover grid
        viewModelScope.launch {
            val isNsfw = preferences.isNsfwModeFlow.first()
            val initialCat = if (isNsfw) "people" else "all"
            val initialPurity = "both"
            val initialQuery = if (isNsfw) "model" else "nature"
            _uiState.value = _uiState.value.copy(
                isNsfwMode = isNsfw,
                discoverCategory = initialCat,
                discoverPurity = initialPurity,
                discoverQuery = initialQuery
            )
            performSearch(initialQuery)
        }

        // Library optimization progress (always on by default: 4K max, WebP)
        viewModelScope.launch {
            repository.optimizationProgress.collect { progress ->
                _uiState.value = _uiState.value.copy(
                    optimizationOptimized = progress.optimized,
                    optimizationTotal = progress.total,
                    isOptimizingLibrary = progress.isRunning
                )
            }
        }
        // Refresh progress and start background optimization on launch
        viewModelScope.launch {
            repository.refreshOptimizationProgress()
            val progress = repository.optimizationProgress.value
            if (progress.total > progress.optimized) {
                repository.compressUnoptimizedWallpapers()
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(discoverQuery = query)
    }

    fun performSearch(customQuery: String? = null) {
        val q = customQuery ?: _uiState.value.discoverQuery
        _uiState.value = _uiState.value.copy(
            discoverQuery = q,
            discoverCurrentPage = 1,
            discoverHasReachedEnd = false,
            searchGeneration = _uiState.value.searchGeneration + 1,
            isDiscoverLoading = true
        )
        viewModelScope.launch {
            val isNsfw = _uiState.value.isNsfwMode
            val cat = _uiState.value.discoverCategory
            val purity = _uiState.value.discoverPurity
            val res = repository.searchWallpapersPaged(
                query = q,
                category = cat,
                purity = purity,
                isNsfwMode = isNsfw,
                page = 1
            )
            res.onSuccess { searchRes ->
                val hasReachedEnd = searchRes.currentPage >= searchRes.lastPage || searchRes.wallpapers.isEmpty()
                _uiState.value = _uiState.value.copy(
                    discoverWallpapers = searchRes.wallpapers,
                    discoverCurrentPage = searchRes.currentPage,
                    discoverLastPage = searchRes.lastPage,
                    discoverTotal = searchRes.total,
                    discoverHasReachedEnd = hasReachedEnd,
                    isDiscoverLoading = false
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    discoverWallpapers = emptyList(),
                    discoverHasReachedEnd = true,
                    isDiscoverLoading = false
                )
            }
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isDiscoverLoading || state.isDiscoverLoadingMore || state.discoverHasReachedEnd) return
        val nextPage = state.discoverCurrentPage + 1
        if (nextPage > state.discoverLastPage) {
            _uiState.value = _uiState.value.copy(discoverHasReachedEnd = true)
            return
        }

        _uiState.value = _uiState.value.copy(isDiscoverLoadingMore = true)
        viewModelScope.launch {
            val res = repository.searchWallpapersPaged(
                query = state.discoverQuery,
                category = state.discoverCategory,
                purity = state.discoverPurity,
                isNsfwMode = state.isNsfwMode,
                page = nextPage
            )
            res.onSuccess { searchRes ->
                val combined = (state.discoverWallpapers + searchRes.wallpapers).distinctBy { it.id }
                val hasReachedEnd = searchRes.currentPage >= searchRes.lastPage || searchRes.wallpapers.isEmpty()
                _uiState.value = _uiState.value.copy(
                    discoverWallpapers = combined,
                    discoverCurrentPage = searchRes.currentPage,
                    discoverLastPage = searchRes.lastPage,
                    discoverTotal = searchRes.total,
                    discoverHasReachedEnd = hasReachedEnd,
                    isDiscoverLoadingMore = false
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(isDiscoverLoadingMore = false)
            }
        }
    }

    fun setDiscoverCategory(category: String) {
        _uiState.value = _uiState.value.copy(discoverCategory = category)
        performSearch()
    }

    fun setDiscoverPurity(purity: String) {
        _uiState.value = _uiState.value.copy(discoverPurity = purity)
        performSearch()
    }

    /**
     * Internal helper keeping the Discover grid in sync with the canonical offset.
     * Grid views are read-only; the offset itself is only changed from Now Playing.
     */
    private fun syncDiscoverOffset(id: String, offset: Float) {
        val clamped = offset.coerceIn(0f, 1f)
        val currentList = _uiState.value.discoverWallpapers
        if (currentList.none { it.id == id }) return
        val updatedList = currentList.map { wp ->
            if (wp.id == id) wp.copy(horizontalOffset = clamped) else wp
        }
        _uiState.value = _uiState.value.copy(discoverWallpapers = updatedList)
    }

    /**
     * Single canonical entry point for manual positioning updates.
     * Called only from the Now Playing view drag handler. Persists to cache,
     * Room (for favorites), and the live current-wallpaper preferences, then
     * broadcasts so the home-screen live wallpaper redraws immediately.
     * Also updates the Discover grid in memory so it never shows a stale offset.
     */
    fun updateCurrentWallpaperOffset(offset: Float) {
        val clamped = offset.coerceIn(0f, 1f)
        val current = _uiState.value.currentWallpaper
        if (current.id.isBlank()) return
        val id = current.id
        _uiState.value = _uiState.value.copy(
            currentWallpaper = current.copy(horizontalOffset = clamped)
        )
        syncDiscoverOffset(id, clamped)
        viewModelScope.launch {
            repository.setCachedOffset(id, clamped)
            repository.updateCropOffset(id, clamped)
            preferences.updateCurrentWallpaperOffset(clamped)
            repository.notifyWallpaperChanged()
        }
    }

    /**
     * Initiates a search in Discover for images similar to a wallpaper by ID using like:id.
     */
    fun searchSimilar(wallpaperId: String) {
        if (wallpaperId.isBlank()) return
        val cleanId = com.example.data.repository.WallpaperRepository.extractWallhavenId(wallpaperId)
        val targetQuery = if (cleanId.isNotBlank()) "like:$cleanId" else "nature"
        _uiState.value = _uiState.value.copy(discoverQuery = targetQuery)
        performSearch(targetQuery)
    }

    fun lazyDetectOffset(wallpaper: Wallpaper) {
        viewModelScope.launch {
            if (repository.isOffsetCached(wallpaper.id)) {
                val cached = repository.getCachedOffset(wallpaper.id) ?: 0.5f
                val currentList = _uiState.value.discoverWallpapers
                val updated = currentList.map { if (it.id == wallpaper.id) it.copy(horizontalOffset = cached) else it }
                _uiState.value = _uiState.value.copy(discoverWallpapers = updated)
                return@launch
            }

            val detected = repository.lazyDetectOffset(
                wallpaper.id,
                wallpaper.thumbUrl.ifBlank { wallpaper.fullUrl }
            )
            val currentList = _uiState.value.discoverWallpapers
            val updated = currentList.map { if (it.id == wallpaper.id) it.copy(horizontalOffset = detected) else it }
            _uiState.value = _uiState.value.copy(discoverWallpapers = updated)
        }
    }

    fun toggleFavoriteWallpaper(wallpaper: Wallpaper) {
        viewModelScope.launch {
            val newFavStatus = repository.toggleFavorite(
                id = wallpaper.id,
                url = wallpaper.fullUrl,
                thumbUrl = wallpaper.thumbUrl,
                purity = wallpaper.purity,
                category = wallpaper.category,
                cropOffset = wallpaper.horizontalOffset,
                tags = wallpaper.tags
            )
            val currentList = _uiState.value.discoverWallpapers
            val updated = currentList.map { if (it.id == wallpaper.id) it.copy(isFavorite = newFavStatus) else it }
            _uiState.value = _uiState.value.copy(discoverWallpapers = updated)
        }
    }

    fun applyWallpaperAsCurrent(wallpaper: Wallpaper) {
        viewModelScope.launch {
            // Tapping a wallpaper applies it as the current wallpaper and pauses the schedule
            preferences.setScheduleEnabled(false)
            WallpaperIntervalWorker.cancel(app)
            val offset = repository.getCachedOffset(wallpaper.id) ?: wallpaper.horizontalOffset
            repository.setCachedOffset(wallpaper.id, offset)
            preferences.setCurrentWallpaper(
                id = wallpaper.id,
                url = wallpaper.fullUrl,
                thumbUrl = wallpaper.thumbUrl,
                purity = wallpaper.purity,
                category = wallpaper.category,
                offset = offset,
                tags = wallpaper.tags
            )
            repository.notifyWallpaperChanged()
        }
    }

    fun toggleMode() {
        val nextMode = !_uiState.value.isNsfwMode
        val nextCat = if (nextMode) "people" else "all"
        val nextPurity = "both"
        val nextQuery = if (nextMode) "model" else "nature"
        viewModelScope.launch {
            preferences.setNsfwMode(nextMode)
            _uiState.value = _uiState.value.copy(
                isNsfwMode = nextMode,
                discoverCategory = nextCat,
                discoverPurity = nextPurity,
                discoverQuery = nextQuery
            )
            performSearch(nextQuery)
        }
    }

    fun fetchNextWallpaper() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.applyNextScheduledFavoriteWallpaper()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun setNsfwPurity(purity: String) {
        viewModelScope.launch {
            preferences.setNsfwPurity(purity)
            setDiscoverPurity(purity)
        }
    }

    fun setCategory(category: String) {
        viewModelScope.launch {
            if (_uiState.value.isNsfwMode) {
                preferences.setNsfwCategory(category)
            } else {
                preferences.setSfwCategory(category)
            }
            setDiscoverCategory(category)
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
                cropOffset = current.horizontalOffset,
                tags = current.tags
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
            // Tapping a favorite applies it as the current wallpaper and disables the schedule until re-enabled
            preferences.setScheduleEnabled(false)
            WallpaperIntervalWorker.cancel(app)
            repository.applyFavorite(fav)
        }
    }

    fun setScheduleEnabled(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            preferences.setScheduleEnabled(enabled)
            if (enabled) {
                val interval = _uiState.value.intervalMinutes.coerceAtLeast(1)
                WallpaperIntervalWorker.scheduleNext(context, interval)
            } else {
                WallpaperIntervalWorker.cancel(context)
            }
        }
    }

    fun updateFilters(grayscale: Float, blur: Float, brightness: Float, vignette: Float) {
        viewModelScope.launch {
            if (_uiState.value.isNsfwMode) {
                preferences.updateNsfwFilters(grayscale, blur, brightness, vignette)
            } else {
                preferences.updateSfwFilters(grayscale, blur, brightness, vignette)
            }
            repository.notifyWallpaperChanged()
        }
    }

    fun resetFilters() {
        updateFilters(
            grayscale = 0.0f,
            blur = 0.0f,
            brightness = 1.0f,
            vignette = 0.0f
        )
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            preferences.setApiKey(key)
        }
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        viewModelScope.launch {
            // Changing or setting an interval also re-enables the automatic schedule
            preferences.setScheduleEnabled(true)
            preferences.setIntervalMinutes(minutes)
            WallpaperIntervalWorker.scheduleNext(context, minutes)
        }
    }

    fun setLocalFolderUri(uri: String) {
        viewModelScope.launch {
            preferences.setLocalFolderUri(uri)
            if (uri.isNotBlank()) {
                loadFavoritesFromFolder(uri)
                startLibraryOptimization()
            }
        }
    }

    fun skipForward() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.applyNextScheduledFavoriteWallpaper()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun skipBackward() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.applyPreviousScheduledFavoriteWallpaper()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            val currentlyPaused = _uiState.value.isSlideshowPaused
            preferences.setSlideshowPaused(!currentlyPaused)
            if (!currentlyPaused) {
                com.example.worker.WallpaperIntervalWorker.cancel(app)
            } else {
                val interval = preferences.intervalMinutesFlow.first().coerceAtLeast(1)
                com.example.worker.WallpaperIntervalWorker.scheduleNext(app, interval)
            }
        }
    }

    /**
     * Starts background library optimization (4K max, WebP) by default.
     * Used when a new folder is selected or progress needs refreshing.
     */
    fun startLibraryOptimization() {
        viewModelScope.launch {
            try {
                repository.refreshOptimizationProgress()
                val progress = repository.optimizationProgress.value
                if (progress.total > progress.optimized || progress.isRunning) {
                    repository.compressUnoptimizedWallpapers()
                } else {
                    // No folder yet or already complete; still call so progress resets cleanly
                    repository.compressUnoptimizedWallpapers()
                }
            } catch (e: Exception) {
                // Optimizer already logs; UI progress will reflect last known state
            }
        }
    }
}

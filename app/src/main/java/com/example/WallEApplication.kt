package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.data.api.NetworkClient
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.repository.WallpaperRepository
import com.example.worker.WallpaperIntervalWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WallEApplication : Application(), ImageLoaderFactory {

    val database by lazy { AppDatabase.getInstance(this) }
    val preferences by lazy { AppPreferences(this) }
    val repository by lazy { WallpaperRepository(this, database, preferences) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { NetworkClient.okHttpClient }
            .crossfade(true)
            .build()
    }

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Start interval worker based on configured interval
        appScope.launch {
            val interval = preferences.intervalMinutesFlow.first()
            WallpaperIntervalWorker.scheduleNext(this@WallEApplication, interval)

            // When the app first loads after being installed, fetch an image matching the nature tag for sfw or the model tag for nsfw
            val current = preferences.currentWallpaperFlow.first()
            if (current.url.isBlank()) {
                val isNsfw = preferences.isNsfwModeFlow.first()
                val targetTag = if (isNsfw) "model" else "nature"
                repository.fetchAndApplyInitialWallpaper(isNsfw, targetTag)
            }
        }
    }
}

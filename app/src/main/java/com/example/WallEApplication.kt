package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.repository.WallpaperRepository
import com.example.worker.WallpaperIntervalWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WallEApplication : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val preferences by lazy { AppPreferences(this) }
    val repository by lazy { WallpaperRepository(this, database, preferences) }

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Start interval worker based on configured interval
        appScope.launch {
            val interval = preferences.intervalMinutesFlow.first()
            WallpaperIntervalWorker.scheduleNext(this@WallEApplication, interval)

            // If current wallpaper is empty, fetch initial wallpaper
            val current = preferences.currentWallpaperFlow.first()
            if (current.url.isBlank()) {
                repository.fetchAndApplyNextWallpaper()
            }
        }
    }
}

package com.example.quicksettings

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.WallEApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WallEModeTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val preferences by lazy {
        (applicationContext as WallEApplication).preferences
    }

    private val repository by lazy {
        (applicationContext as WallEApplication).repository
    }

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            val isNsfw = preferences.isNsfwModeFlow.first()
            updateTileDisplay(isNsfw)
        }
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val currentMode = preferences.isNsfwModeFlow.first()
            val newMode = !currentMode
            preferences.setNsfwMode(newMode)
            updateTileDisplay(newMode)

            // Automatically fetch and apply wallpaper for the newly activated mode
            repository.fetchAndApplyNextWallpaper()
        }
    }

    private fun updateTileDisplay(isNsfw: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isNsfw) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wall-e"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isNsfw) "NSFW" else "SFW"
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

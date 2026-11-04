package com.example.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.WallEApplication
import com.example.data.local.CurrentWallpaperInfo
import com.example.data.model.ModeFilterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class WallELiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return WallEEngine()
    }

    inner class WallEEngine : Engine() {
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val imageLoader by lazy { ImageLoader(this@WallELiveWallpaperService) }
        private val preferences by lazy { (applicationContext as WallEApplication).preferences }
        private val repository by lazy { (applicationContext as WallEApplication).repository }

        private var currentBitmap: Bitmap? = null
        private var currentInfo: CurrentWallpaperInfo? = null
        private var isNsfwMode: Boolean = false
        private var activeFilters: ModeFilterSettings = ModeFilterSettings()

        // Double tap detection state
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private val doubleTapThreshold = 350L
        private val doubleTapDistance = 120f

        // Heart animation state
        private var heartAnimStartTime = 0L
        private var heartAnimX = 0f
        private var heartAnimY = 0f
        private var heartIsFavorited = true
        private var isHeartAnimating = false
        private val heartAnimDuration = 800L
        private val animHandler = Handler(Looper.getMainLooper())
        private val animRunnable = object : Runnable {
            override fun run() {
                if (!isHeartAnimating) return
                val elapsed = SystemClock.uptimeMillis() - heartAnimStartTime
                if (elapsed >= heartAnimDuration) {
                    isHeartAnimating = false
                    drawFrame()
                } else {
                    drawFrame()
                    animHandler.postDelayed(this, 16)
                }
            }
        }

        private val updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                reloadWallpaperAndRedraw()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            val filter = IntentFilter("com.example.walle.ACTION_WALLPAPER_UPDATED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(updateReceiver, filter)
            }

            observePreferences()
        }

        override fun onDestroy() {
            super.onDestroy()
            isHeartAnimating = false
            animHandler.removeCallbacks(animRunnable)
            try {
                unregisterReceiver(updateReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            serviceScope.cancel()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                reloadWallpaperAndRedraw()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        private fun observePreferences() {
            serviceScope.launch {
                preferences.isNsfwModeFlow.collect { nsfw ->
                    isNsfwMode = nsfw
                    activeFilters = if (nsfw) preferences.nsfwFilterFlow.first() else preferences.sfwFilterFlow.first()
                    drawFrame()
                }
            }
            serviceScope.launch {
                preferences.sfwFilterFlow.collect { sfwFilter ->
                    if (!isNsfwMode) {
                        activeFilters = sfwFilter
                        drawFrame()
                    }
                }
            }
            serviceScope.launch {
                preferences.nsfwFilterFlow.collect { nsfwFilter ->
                    if (isNsfwMode) {
                        activeFilters = nsfwFilter
                        drawFrame()
                    }
                }
            }
            serviceScope.launch {
                preferences.currentWallpaperFlow.collect { info ->
                    currentInfo = info
                    loadBitmap(info.url)
                }
            }
        }

        private fun reloadWallpaperAndRedraw() {
            serviceScope.launch {
                val info = preferences.currentWallpaperFlow.first()
                val nsfw = preferences.isNsfwModeFlow.first()
                isNsfwMode = nsfw
                activeFilters = if (nsfw) preferences.nsfwFilterFlow.first() else preferences.sfwFilterFlow.first()
                currentInfo = info
                loadBitmap(info.url)
            }
        }

        private fun loadBitmap(url: String) {
            if (url.isBlank()) {
                drawFrame()
                return
            }
            serviceScope.launch {
                try {
                    val request = ImageRequest.Builder(this@WallELiveWallpaperService)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                    val bmp = (result as? BitmapDrawable)?.bitmap
                    if (bmp != null) {
                        currentBitmap = bmp
                        drawFrame()
                    }
                } catch (e: Exception) {
                    Log.e("WallELiveWallpaper", "Failed to load bitmap: $url", e)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event == null) return

            if (event.action == MotionEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                val dx = abs(event.x - lastTapX)
                val dy = abs(event.y - lastTapY)

                if (now - lastTapTime <= doubleTapThreshold && dx <= doubleTapDistance && dy <= doubleTapDistance) {
                    // Double tap triggered!
                    handleDoubleTap(event.x, event.y)
                    lastTapTime = 0L
                } else {
                    lastTapTime = now
                    lastTapX = event.x
                    lastTapY = event.y
                }
            }
        }

        private fun handleDoubleTap(x: Float, y: Float) {
            val info = currentInfo ?: return
            if (info.id.isBlank()) return

            serviceScope.launch {
                // Toggle favorite in repository
                val isNowFavorite = repository.toggleFavorite(
                    id = info.id,
                    url = info.url,
                    thumbUrl = info.thumbUrl,
                    purity = info.purity,
                    category = info.category,
                    cropOffset = info.horizontalOffset
                )

                // Trigger heart feedback animation
                withContext(Dispatchers.Main) {
                    heartAnimX = x
                    heartAnimY = y
                    heartIsFavorited = isNowFavorite
                    heartAnimStartTime = SystemClock.uptimeMillis()
                    isHeartAnimating = true
                    animHandler.removeCallbacks(animRunnable)
                    animHandler.post(animRunnable)
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val surfaceFrame = holder.surfaceFrame
                    val width = surfaceFrame.width()
                    val height = surfaceFrame.height()

                    val bmp = currentBitmap
                    if (bmp != null) {
                        val offset = currentInfo?.horizontalOffset ?: 0.5f
                        WallpaperRenderHelper.renderWallpaperToCanvas(
                            canvas = canvas,
                            bitmap = bmp,
                            screenWidth = width,
                            screenHeight = height,
                            horizontalOffset = offset,
                            filters = activeFilters
                        )
                    } else {
                        // Minimalist background if no wallpaper yet
                        canvas.drawColor(Color.parseColor("#121316"))
                    }

                    // Render heart feedback animation if active
                    if (isHeartAnimating) {
                        val elapsed = SystemClock.uptimeMillis() - heartAnimStartTime
                        val progress = (elapsed.toFloat() / heartAnimDuration.toFloat()).coerceIn(0f, 1f)
                        WallpaperRenderHelper.drawHeartFeedback(
                            canvas = canvas,
                            x = heartAnimX,
                            y = heartAnimY,
                            isFavorited = heartIsFavorited,
                            progress = progress
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("WallELiveWallpaper", "Error drawing frame", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // Surface was destroyed
                    }
                }
            }
        }
    }
}

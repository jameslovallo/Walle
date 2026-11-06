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
import com.example.worker.WallpaperIntervalWorker
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

        // Double tap & long press gesture detection state
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private val doubleTapThreshold = 350L
        private val doubleTapDistance = 120f

        private var touchDownX = 0f
        private var touchDownY = 0f
        private var isLongPressFired = false
        private val longPressThreshold = 550L
        private val longPressRunnable = Runnable {
            isLongPressFired = true
            handleLongPress(touchDownX, touchDownY)
        }

        // Action feedback animation state (Skip, Pause, Resume)
        private var feedbackAnimStartTime = 0L
        private var feedbackAnimX = 0f
        private var feedbackAnimY = 0f
        private var feedbackIconType = WallpaperRenderHelper.FeedbackIconType.SKIP
        private var isFeedbackAnimating = false
        private val feedbackAnimDuration = 800L
        private val animHandler = Handler(Looper.getMainLooper())
        private val animRunnable = object : Runnable {
            override fun run() {
                if (!isFeedbackAnimating) return
                val elapsed = SystemClock.uptimeMillis() - feedbackAnimStartTime
                if (elapsed >= feedbackAnimDuration) {
                    isFeedbackAnimating = false
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
            isFeedbackAnimating = false
            animHandler.removeCallbacks(animRunnable)
            animHandler.removeCallbacks(longPressRunnable)
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
                        .size(coil.size.Size.ORIGINAL)
                        .precision(coil.size.Precision.EXACT)
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

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    isLongPressFired = false
                    animHandler.removeCallbacks(longPressRunnable)
                    animHandler.postDelayed(longPressRunnable, longPressThreshold)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - touchDownX)
                    val dy = abs(event.y - touchDownY)
                    if (dx > 45f || dy > 45f) {
                        animHandler.removeCallbacks(longPressRunnable)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    animHandler.removeCallbacks(longPressRunnable)
                    if (!isLongPressFired) {
                        val now = SystemClock.uptimeMillis()
                        val dx = abs(event.x - lastTapX)
                        val dy = abs(event.y - lastTapY)

                        if (now - lastTapTime <= doubleTapThreshold && dx <= doubleTapDistance && dy <= doubleTapDistance) {
                            // Double tap triggered: skip to next favorite wallpaper and reset timer
                            handleDoubleTap(event.x, event.y)
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    animHandler.removeCallbacks(longPressRunnable)
                }
            }
        }

        private fun handleDoubleTap(x: Float, y: Float) {
            serviceScope.launch {
                // Skip to the next wallpaper from saved favorites
                repository.applyNextScheduledFavoriteWallpaper()

                // Reset the schedule timer
                val isPaused = preferences.isSlideshowPausedFlow.first()
                if (!isPaused) {
                    val interval = preferences.intervalMinutesFlow.first().coerceAtLeast(1)
                    WallpaperIntervalWorker.scheduleNext(this@WallELiveWallpaperService, interval)
                }

                // Show skip icon where user tapped
                withContext(Dispatchers.Main) {
                    feedbackIconType = WallpaperRenderHelper.FeedbackIconType.SKIP
                    feedbackAnimX = x
                    feedbackAnimY = y
                    feedbackAnimStartTime = SystemClock.uptimeMillis()
                    isFeedbackAnimating = true
                    animHandler.removeCallbacks(animRunnable)
                    animHandler.post(animRunnable)
                }
            }
        }

        private fun handleLongPress(x: Float, y: Float) {
            serviceScope.launch {
                val wasPaused = preferences.isSlideshowPausedFlow.first()
                val nowPaused = !wasPaused
                preferences.setSlideshowPaused(nowPaused)

                if (nowPaused) {
                    WallpaperIntervalWorker.cancel(this@WallELiveWallpaperService)
                } else {
                    val interval = preferences.intervalMinutesFlow.first().coerceAtLeast(1)
                    WallpaperIntervalWorker.scheduleNext(this@WallELiveWallpaperService, interval)
                }

                // Show Pause or Resume feedback icon
                withContext(Dispatchers.Main) {
                    feedbackIconType = if (nowPaused) {
                        WallpaperRenderHelper.FeedbackIconType.PAUSE
                    } else {
                        WallpaperRenderHelper.FeedbackIconType.RESUME
                    }
                    feedbackAnimX = x
                    feedbackAnimY = y
                    feedbackAnimStartTime = SystemClock.uptimeMillis()
                    isFeedbackAnimating = true
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

                    // Render action feedback animation (Skip, Pause, Resume) if active
                    if (isFeedbackAnimating) {
                        val elapsed = SystemClock.uptimeMillis() - feedbackAnimStartTime
                        val progress = (elapsed.toFloat() / feedbackAnimDuration.toFloat()).coerceIn(0f, 1f)
                        WallpaperRenderHelper.drawFeedbackIcon(
                            canvas = canvas,
                            x = feedbackAnimX,
                            y = feedbackAnimY,
                            type = feedbackIconType,
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

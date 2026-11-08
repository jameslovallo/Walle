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

        // Fade transition state when wallpaper changes
        private var previousBitmap: Bitmap? = null
        private var previousOffset: Float = 0.5f
        private var fadeAnimStartTime = 0L
        private var isFadeAnimating = false
        private val fadeAnimDuration = 650L // Smooth, seamless crossfade

        // Double tap & long press gesture detection state
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private val doubleTapThreshold = 350L
        private val doubleTapDistance = 120f

        private var touchDownX = 0f
        private var touchDownY = 0f
        private var isLongPressFired = false
        private val longPressThreshold = 1100L
        private val longPressRunnable = Runnable {
            isLongPressFired = true
            handleLongPress(touchDownX, touchDownY)
        }

        // Action feedback animation state (Skip)
        private var feedbackAnimStartTime = 0L
        private var feedbackAnimX = 0f
        private var feedbackAnimY = 0f
        private var feedbackIconType = WallpaperRenderHelper.FeedbackIconType.SKIP
        private var isFeedbackAnimating = false
        private val feedbackAnimDuration = 800L
        private val animHandler = Handler(Looper.getMainLooper())
        private val animRunnable = object : Runnable {
            override fun run() {
                var shouldContinue = false
                val now = SystemClock.uptimeMillis()

                if (isFadeAnimating) {
                    val elapsed = now - fadeAnimStartTime
                    if (elapsed >= fadeAnimDuration) {
                        isFadeAnimating = false
                        previousBitmap = null
                    } else {
                        shouldContinue = true
                    }
                }

                if (isFeedbackAnimating) {
                    val elapsed = now - feedbackAnimStartTime
                    if (elapsed >= feedbackAnimDuration) {
                        isFeedbackAnimating = false
                    } else {
                        shouldContinue = true
                    }
                }

                drawFrame()

                if (shouldContinue) {
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
            isFadeAnimating = false
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
                    loadAndTransitionToWallpaper(info)
                }
            }
        }

        private fun reloadWallpaperAndRedraw() {
            serviceScope.launch {
                val info = preferences.currentWallpaperFlow.first()
                val nsfw = preferences.isNsfwModeFlow.first()
                isNsfwMode = nsfw
                activeFilters = if (nsfw) preferences.nsfwFilterFlow.first() else preferences.sfwFilterFlow.first()
                loadAndTransitionToWallpaper(info)
            }
        }

        private fun loadAndTransitionToWallpaper(newInfo: CurrentWallpaperInfo) {
            if (newInfo.url.isBlank()) {
                drawFrame()
                return
            }

            // If same wallpaper and same offset, don't re-animate
            if (currentInfo?.id == newInfo.id && currentInfo?.url == newInfo.url && currentInfo?.horizontalOffset == newInfo.horizontalOffset && currentBitmap != null) {
                return
            }

            // If only offset changed on the same image, update directly without reload
            if (currentInfo?.id == newInfo.id && currentBitmap != null) {
                currentInfo = newInfo
                drawFrame()
                return
            }

            serviceScope.launch {
                try {
                    // Pre-load the next image completely into memory before changing the position offset or image
                    val request = ImageRequest.Builder(this@WallELiveWallpaperService)
                        .data(newInfo.url)
                        .size(coil.size.Size.ORIGINAL)
                        .precision(coil.size.Precision.EXACT)
                        .allowHardware(false)
                        .build()
                    val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                    val nextBmp = (result as? BitmapDrawable)?.bitmap

                    if (nextBmp != null) {
                        withContext(Dispatchers.Main) {
                            if (currentBitmap != null) {
                                // Seamless crossfade transition between wallpapers
                                previousBitmap = currentBitmap
                                previousOffset = currentInfo?.horizontalOffset ?: 0.5f
                                currentBitmap = nextBmp
                                currentInfo = newInfo
                                fadeAnimStartTime = SystemClock.uptimeMillis()
                                isFadeAnimating = true
                                animHandler.removeCallbacks(animRunnable)
                                animHandler.post(animRunnable)
                            } else {
                                currentBitmap = nextBmp
                                currentInfo = newInfo
                                drawFrame()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallELiveWallpaper", "Failed to preload bitmap: ${newInfo.url}", e)
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

        /**
         * Holding an empty space opens the app and moves directly to the current image / now playing view.
         * The pause/play interaction on hold is removed as requested.
         */
        private fun handleLongPress(x: Float, y: Float) {
            try {
                val launchIntent = Intent(this@WallELiveWallpaperService, com.example.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_now_playing", true)
                }
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("WallELiveWallpaper", "Error launching app from long press", e)
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

                        if (isFadeAnimating) {
                            val elapsed = SystemClock.uptimeMillis() - fadeAnimStartTime
                            val progress = (elapsed.toFloat() / fadeAnimDuration.toFloat()).coerceIn(0f, 1f)
                            WallpaperRenderHelper.renderWallpaperFadeTransitionToCanvas(
                                canvas = canvas,
                                oldBitmap = previousBitmap,
                                oldOffset = previousOffset,
                                newBitmap = bmp,
                                newOffset = offset,
                                screenWidth = width,
                                screenHeight = height,
                                progress = progress,
                                filters = activeFilters
                            )
                        } else {
                            WallpaperRenderHelper.renderWallpaperToCanvas(
                                canvas = canvas,
                                bitmap = bmp,
                                screenWidth = width,
                                screenHeight = height,
                                horizontalOffset = offset,
                                filters = activeFilters
                            )
                        }
                    } else {
                        // Minimalist background if no wallpaper yet
                        canvas.drawColor(Color.parseColor("#121316"))
                    }

                    // Render action feedback animation (Skip) if active
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

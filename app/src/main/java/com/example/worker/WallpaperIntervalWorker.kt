package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.WallEApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class WallpaperIntervalWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WallEApplication
        val repository = app.repository
        val preferences = app.preferences

        val isScheduleEnabled = preferences.isScheduleEnabledFlow.first()
        if (!isScheduleEnabled) {
            return Result.success()
        }

        try {
            repository.fetchAndApplyNextWallpaper()
        } catch (e: Exception) {
            // Keep going
        }

        // Reschedule next cycle only if schedule is still enabled
        if (preferences.isScheduleEnabledFlow.first()) {
            val intervalMinutes = preferences.intervalMinutesFlow.first().coerceAtLeast(1)
            scheduleNext(applicationContext, intervalMinutes)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "walle_interval_wallpaper_work"

        fun scheduleNext(context: Context, intervalMinutes: Int) {
            val workRequest = OneTimeWorkRequestBuilder<WallpaperIntervalWorker>()
                .setInitialDelay(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

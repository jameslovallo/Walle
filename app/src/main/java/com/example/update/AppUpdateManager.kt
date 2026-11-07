package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.api.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val apkDownloadUrl: String = "",
    val apkFileName: String = ""
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    /**
     * Checks GitHub API for the latest release in the repository (e.g. "lovallo/WallHeaven").
     * Compares latestVersion against currentVersion.
     */
    suspend fun checkForUpdate(
        currentVersionName: String,
        repository: String = "lovallo/WallHeaven"
    ): AppUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val cleanRepo = repository.trim().removePrefix("https://github.com/").removeSuffix("/")
            val url = "https://api.github.com/repos/$cleanRepo/releases/latest"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WallHeaven-Android-App")
                .build()

            val response = NetworkClient.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Update check response unsuccessful: ${response.code}")
                return@withContext AppUpdateInfo(currentVersion = currentVersionName)
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext AppUpdateInfo(currentVersion = currentVersionName)
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val releaseNotes = json.optString("body", "").trim()

            // Look through assets for .apk file
            val assets = json.optJSONArray("assets")
            var downloadUrl = ""
            var apkName = ""

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        apkName = name
                        break
                    }
                }
            }

            val isNewer = isVersionNewer(currentVersion = currentVersionName, latestTag = tagName)
            Log.d(TAG, "Current: $currentVersionName, Latest: $tagName, IsNewer: $isNewer, Asset: $downloadUrl")

            AppUpdateInfo(
                isUpdateAvailable = isNewer && downloadUrl.isNotBlank(),
                latestVersion = tagName,
                currentVersion = currentVersionName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = downloadUrl,
                apkFileName = if (apkName.isNotBlank()) apkName else "WallHeaven-$tagName.apk"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            AppUpdateInfo(currentVersion = currentVersionName)
        }
    }

    /**
     * Downloads the APK file into the app's cache directory and invokes the Android Package Installer.
     */
    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "WallHeaven-Android-App")
                .build()

            val response = NetworkClient.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${response.code} downloading APK"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download response body"))
            val contentLength = body.contentLength()

            val cacheDir = context.cacheDir
            val destinationFile = File(cacheDir, fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                            withContext(Dispatchers.Main) {
                                onProgress(progress.coerceIn(0f, 1f))
                            }
                        }
                    }
                    output.flush()
                }
            }

            // Launch Package Installer via FileProvider
            withContext(Dispatchers.Main) {
                val apkUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destinationFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download and install APK: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Compares version strings like "1.1" vs "v1.1.1" or "1.1.0" vs "v1.2.0".
     */
    fun isVersionNewer(currentVersion: String, latestTag: String): Boolean {
        if (latestTag.isBlank()) return false
        val cleanCurrent = currentVersion.trim().removePrefix("v")
        val cleanLatest = latestTag.trim().removePrefix("v")

        if (cleanCurrent == cleanLatest) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat > curr) return true
            if (lat < curr) return false
        }
        return false
    }
}

package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_wallpapers")
data class FavoriteWallpaper(
    @PrimaryKey
    val id: String, // Wallhaven ID or filename
    val url: String,
    val thumbUrl: String,
    val purity: String, // "sfw", "sketchy", "nsfw"
    val category: String, // "general", "anime", "people"
    val resolution: String = "",
    val horizontalOffset: Float = 0.5f,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val localFilePath: String? = null,
    val tags: String = ""
)

package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WallhavenSearchResponse(
    @Json(name = "data") val data: List<WallhavenImageItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WallhavenImageItem(
    @Json(name = "id") val id: String,
    @Json(name = "url") val url: String = "",
    @Json(name = "path") val path: String = "",
    @Json(name = "purity") val purity: String = "sfw", // "sfw", "sketchy", "nsfw"
    @Json(name = "category") val category: String = "general", // "general", "anime", "people"
    @Json(name = "resolution") val resolution: String = "",
    @Json(name = "ratio") val ratio: String = "",
    @Json(name = "thumbs") val thumbs: ThumbsItem? = null
)

@JsonClass(generateAdapter = true)
data class ThumbsItem(
    @Json(name = "large") val large: String? = null,
    @Json(name = "original") val original: String? = null,
    @Json(name = "small") val small: String? = null
)

data class Wallpaper(
    val id: String,
    val fullUrl: String,
    val thumbUrl: String,
    val purity: String,
    val category: String,
    val resolution: String,
    val horizontalOffset: Float = 0.5f,
    val isFavorite: Boolean = false
)

data class ModeFilterSettings(
    val grayscale: Float = 0f,    // 0f (full color) to 1f (full grayscale)
    val blur: Float = 0f,         // 0f (no blur) to 1f (max blur)
    val brightness: Float = 1.0f, // 0f (black) to 1f (full opacity over black)
    val vignette: Float = 0f      // 0f (no vignette) to 1f (full vignette)
)

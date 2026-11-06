package com.example.data.api

import com.example.data.model.WallhavenSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Replacement Wallhaven image API.
 *
 * Single unified search call. The caller (WallpaperRepository) builds
 * the `q` parameter so that all terms are included as plain tags joined
 * with a single space and no `+` (must-have) or `-` (exclude) operators.
 * That keeps the result set maximally inclusive for the requested terms.
 *
 * `sorting = "relevance"` (with `order = "desc"`) is used instead of
 * `toplist` / `random` so the API returns every relevant wallpaper in
 * relevance order rather than only the recent top wallpapers.
 *
 * No restrictive `atleast` / `resolutions` / `ratios` / `colors` params
 * are sent, since those would filter out otherwise relevant results.
 * Category and purity codes are still supplied by the caller exactly
 * as before — the UI and filtering logic are unchanged.
 */
interface WallhavenApiService {
    @GET("api/v1/search")
    suspend fun searchWallpapers(
        @Query("q") query: String? = null,
        @Query("categories") categories: String = "111",
        @Query("purity") purity: String = "100",
        @Query("sorting") sorting: String = "relevance",
        @Query("order") order: String = "desc",
        @Query("page") page: Int = 1,
        @Query("apikey") apiKey: String? = null
    ): WallhavenSearchResponse
}

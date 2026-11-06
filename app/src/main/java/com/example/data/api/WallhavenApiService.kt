package com.example.data.api

import com.example.data.model.WallhavenSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Single Wallhaven search call.
 *
 * Broadest configuration for maximum results:
 * - [categories] = "111" (general + anime + people)
 * - [sorting] = "relevance" (toplist/date_added restrict the pool too far)
 * - [query] is built in the repository by joining search terms with a
 *   single space. No `+` (must-have) or `-` (exclude) operators are added:
 *   `+a +b` requires every term and collapses the result count.
 */
interface WallhavenApiService {
    @GET("api/v1/search")
    suspend fun searchWallpapers(
        @Query("q") query: String? = null,
        @Query("categories") categories: String = "111",
        @Query("purity") purity: String,
        @Query("sorting") sorting: String = "relevance",
        @Query("order") order: String = "desc",
        @Query("page") page: Int = 1,
        @Query("apikey") apiKey: String? = null
    ): WallhavenSearchResponse
}

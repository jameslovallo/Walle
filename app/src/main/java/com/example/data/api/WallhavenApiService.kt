package com.example.data.api

import com.example.data.model.WallhavenDetailResponse
import com.example.data.model.WallhavenSearchResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Wallhaven image and details API.
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

    @GET("api/v1/w/{id}")
    suspend fun getWallpaperDetails(
        @Path("id") id: String,
        @Query("apikey") apiKey: String? = null
    ): WallhavenDetailResponse
}

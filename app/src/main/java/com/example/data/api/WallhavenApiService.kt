package com.example.data.api

import com.example.data.model.WallhavenSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface WallhavenApiService {
    @GET("api/v1/search")
    suspend fun searchWallpapers(
        @Query("q") query: String? = null,
        @Query("categories") categories: String? = "111", // general/anime/people
        @Query("purity") purity: String? = "100", // sfw/sketchy/nsfw
        @Query("sorting") sorting: String = "random",
        @Query("apikey") apiKey: String? = null
    ): WallhavenSearchResponse
}

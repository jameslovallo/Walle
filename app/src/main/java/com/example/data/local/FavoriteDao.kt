package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_wallpapers WHERE LOWER(purity) IN (:purities) OR (purity = '' AND 'sfw' IN (:purities)) ORDER BY addedTimestamp DESC")
    fun getFavoritesByPurities(purities: List<String>): Flow<List<FavoriteWallpaper>>

    @Query("SELECT * FROM favorite_wallpapers WHERE LOWER(purity) IN (:purities) OR (purity = '' AND 'sfw' IN (:purities)) ORDER BY addedTimestamp DESC")
    suspend fun getFavoritesByPuritiesDirect(purities: List<String>): List<FavoriteWallpaper>

    @Query("SELECT * FROM favorite_wallpapers WHERE purity IN (:purities) ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavorite(purities: List<String>): FavoriteWallpaper?

    @Query("SELECT * FROM favorite_wallpapers WHERE id = :id LIMIT 1")
    suspend fun getFavoriteById(id: String): FavoriteWallpaper?

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_wallpapers WHERE id = :id)")
    fun isFavorite(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_wallpapers WHERE id = :id)")
    suspend fun isFavoriteDirect(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteWallpaper)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteWallpaper>)

    @Query("DELETE FROM favorite_wallpapers WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM favorite_wallpapers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM favorite_wallpapers")
    suspend fun getAllFavoritesDirect(): List<FavoriteWallpaper>

    /**
     * Single transaction for index sync: one Flow emission for the grid
     * instead of one per row (critical for large libraries).
     */
    @androidx.room.Transaction
    suspend fun syncFavorites(upserts: List<FavoriteWallpaper>, deletes: List<String>) {
        if (deletes.isNotEmpty()) deleteByIds(deletes)
        if (upserts.isNotEmpty()) insertFavorites(upserts)
    }

    @Query("UPDATE favorite_wallpapers SET horizontalOffset = :offset WHERE id = :id")
    suspend fun updateCropOffset(id: String, offset: Float)

    @Query("UPDATE favorite_wallpapers SET localFilePath = :path WHERE id = :id")
    suspend fun updateLocalFilePath(id: String, path: String)

    @Query("SELECT id FROM favorite_wallpapers")
    suspend fun getAllFavoriteIds(): List<String>
}

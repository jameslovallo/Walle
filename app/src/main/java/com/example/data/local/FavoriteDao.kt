package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_wallpapers ORDER BY addedTimestamp DESC")
    fun getAllFavorites(): Flow<List<FavoriteWallpaper>>

    @Query("SELECT * FROM favorite_wallpapers WHERE LOWER(purity) IN (:purities) OR (purity = '' AND 'sfw' IN (:purities)) ORDER BY addedTimestamp DESC")
    fun getFavoritesByPurities(purities: List<String>): Flow<List<FavoriteWallpaper>>

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

    @Query("DELETE FROM favorite_wallpapers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE favorite_wallpapers SET horizontalOffset = :offset WHERE id = :id")
    suspend fun updateCropOffset(id: String, offset: Float)

    @Query("SELECT id FROM favorite_wallpapers")
    suspend fun getAllFavoriteIds(): List<String>
}

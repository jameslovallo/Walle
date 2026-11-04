package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.FavoriteWallpaper
import com.example.data.model.ModeFilterSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context verifies Wall-e name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Wall-e", appName)
    }

    @Test
    fun `insert and retrieve favorite wallpaper with crop offset`() = runBlocking {
        val dao = db.favoriteDao()
        val fav = FavoriteWallpaper(
            id = "w12345",
            url = "https://w.wallhaven.cc/full/w1/wallhaven-w12345.jpg",
            thumbUrl = "https://th.wallhaven.cc/lg/w1/w12345.jpg",
            purity = "sfw",
            category = "general",
            horizontalOffset = 0.72f
        )
        dao.insertFavorite(fav)

        assertTrue(dao.isFavoriteDirect("w12345"))
        assertFalse(dao.isFavoriteDirect("non_existent"))

        val retrieved = dao.getFavoriteById("w12345")
        assertNotNull(retrieved)
        assertEquals(0.72f, retrieved?.horizontalOffset)

        // Test non-destructive crop update
        dao.updateCropOffset("w12345", 0.45f)
        val updated = dao.getFavoriteById("w12345")
        assertEquals(0.45f, updated?.horizontalOffset)

        // Test delete
        dao.deleteById("w12345")
        assertFalse(dao.isFavoriteDirect("w12345"))
    }

    @Test
    fun `test filtering favorites by purity`() = runBlocking {
        val dao = db.favoriteDao()
        dao.insertFavorite(
            FavoriteWallpaper(
                id = "sfw_1",
                url = "https://w.wallhaven.cc/1.jpg",
                thumbUrl = "https://th.wallhaven.cc/1.jpg",
                purity = "sfw",
                category = "anime"
            )
        )
        dao.insertFavorite(
            FavoriteWallpaper(
                id = "nsfw_1",
                url = "https://w.wallhaven.cc/2.jpg",
                thumbUrl = "https://th.wallhaven.cc/2.jpg",
                purity = "nsfw",
                category = "people"
            )
        )
        dao.insertFavorite(
            FavoriteWallpaper(
                id = "sketchy_1",
                url = "https://w.wallhaven.cc/3.jpg",
                thumbUrl = "https://th.wallhaven.cc/3.jpg",
                purity = "sketchy",
                category = "people"
            )
        )

        val sfwList = dao.getFavoritesByPurities(listOf("sfw")).first()
        assertEquals(1, sfwList.size)
        assertEquals("sfw_1", sfwList[0].id)

        val nsfwList = dao.getFavoritesByPurities(listOf("sketchy", "nsfw")).first()
        assertEquals(2, nsfwList.size)
    }

    @Test
    fun `test filter settings defaults`() {
        val filters = ModeFilterSettings()
        assertEquals(1.0f, filters.brightness)
        assertEquals(0f, filters.blur)
        assertEquals(0f, filters.vignette)
        assertEquals(1.0f, filters.contrast)
    }
}

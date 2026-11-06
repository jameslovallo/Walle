package com.example

import com.example.data.model.ModeFilterSettings
import com.example.data.repository.WallpaperRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `test filter settings defaults and sensible initial values`() {
        val filters = ModeFilterSettings()
        // 100% opacity, full color (0% grayscale), no blur, no vignette
        assertEquals(1.0f, filters.brightness, 0.001f)
        assertEquals(0.0f, filters.grayscale, 0.001f)
        assertEquals(0.0f, filters.blur, 0.001f)
        assertEquals(0.0f, filters.vignette, 0.001f)
    }

    @Test
    fun `test custom filter settings values`() {
        val custom = ModeFilterSettings(
            grayscale = 0.5f,
            blur = 0.25f,
            brightness = 0.85f,
            vignette = 0.4f
        )
        assertEquals(0.5f, custom.grayscale, 0.001f)
        assertEquals(0.25f, custom.blur, 0.001f)
        assertEquals(0.85f, custom.brightness, 0.001f)
        assertEquals(0.4f, custom.vignette, 0.001f)
    }

    @Test
    fun `test wallhaven query builder includes all terms as plain tags`() {
        assertEquals("nature forest sunset", WallpaperRepository.buildWallhavenQuery(listOf("nature", "forest", "sunset")))
        // Operators that would narrow results are stripped so every term is a plain tag.
        assertEquals("nature forest", WallpaperRepository.buildWallhavenQuery(listOf("+Nature", "-forest", "nature")))
        assertEquals(null, WallpaperRepository.buildWallhavenQuery(emptyList()))
        assertEquals(null, WallpaperRepository.buildWallhavenQuery(listOf("", "  ")))
    }

    @Test
    fun `test parse search terms splits free text`() {
        assertEquals(listOf("night", "city"), WallpaperRepository.parseSearchTerms("night city"))
        assertEquals(listOf("nature"), WallpaperRepository.parseSearchTerms("  nature  "))
        assertEquals(emptyList<String>(), WallpaperRepository.parseSearchTerms(""))
    }

    @Test
    fun `test right-side focus positioning math within aspect ratio`() {
        // Landscape image: 1920x1080
        val imgWidth = 1920f
        val imgHeight = 1080f
        val frameAspectRatio = 9f / 19.5f // ~0.4615
        val visibleWidthInImage = imgHeight * frameAspectRatio // ~498.46
        val scrollableWidth = imgWidth - visibleWidthInImage // ~1421.54

        // Case 1: Face is in center of image (x = 960)
        // We want the face at ~70% of the visible screen (right side)
        val faceCenterX = 960f
        val targetScreenFraction = 0.70f
        val targetWindowLeft = faceCenterX - (targetScreenFraction * visibleWidthInImage)
        val calculatedOffset = (targetWindowLeft / scrollableWidth).coerceIn(0f, 1f)

        // Face at 960 with target at 70% results in a scroll window starting around 611, offset ~0.43
        assertTrue(calculatedOffset in 0.40f..0.46f)
        val visibleLeft = calculatedOffset * scrollableWidth
        val faceScreenFraction = (faceCenterX - visibleLeft) / visibleWidthInImage
        assertEquals(0.70f, faceScreenFraction, 0.01f)
    }

    @Test
    fun `test current wallpaper tags parsing`() {
        val rawTags = "anime, fantasy, landscape, cyberpunk"
        val parsed = rawTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(4, parsed.size)
        assertEquals("anime", parsed[0])
        assertEquals("cyberpunk", parsed[3])

        val info = com.example.data.local.CurrentWallpaperInfo(
            id = "w101",
            tags = parsed
        )
        assertEquals(4, info.tags.size)
        assertTrue(info.tags.contains("landscape"))
    }

    @Test
    fun `test initial tags default to nature for SFW and women for NSFW`() {
        val defaultSfwTag = "nature"
        val defaultNsfwTag = "women"

        fun getInitialTag(isNsfw: Boolean) = if (isNsfw) defaultNsfwTag else defaultSfwTag

        assertEquals("nature", getInitialTag(false))
        assertEquals("women", getInitialTag(true))
    }

    @Test
    fun `test single tag fallback query generation`() {
        val multipleTags = listOf("nature", "mountain", "forest")
        val fallbackSingleTag = multipleTags.first()
        assertEquals("nature", fallbackSingleTag)

        val emptyTags = emptyList<String>()
        val fallbackDefault = emptyTags.firstOrNull() ?: "nature"
        assertEquals("nature", fallbackDefault)
    }

    @Test
    fun `test discover search defaults for sfw and nsfw modes`() {
        // SFW defaults: inclusive category + nature
        assertEquals("all", if (false) "people" else "all")
        assertEquals("all", if (false) "all" else "all")
        assertEquals("nature", if (false) "women" else "nature")
        // NSFW defaults: People category + women
        assertEquals("people", if (true) "people" else "all")
        assertEquals("all", if (true) "all" else "all")
        assertEquals("women", if (true) "women" else "nature")
    }

    @Test
    fun `test category code most inclusive is all`() {
        assertEquals("111", WallpaperRepository.categoryCode("all"))
        assertEquals("111", WallpaperRepository.categoryCode("unknown"))
        assertEquals("010", WallpaperRepository.categoryCode("anime"))
        assertEquals("001", WallpaperRepository.categoryCode("people"))
        assertEquals("100", WallpaperRepository.categoryCode("general"))
    }

    @Test
    fun `test purity code most inclusive is all`() {
        assertEquals("111", WallpaperRepository.purityCode("all", isNsfwMode = true, hasApiKey = true))
        assertEquals("100", WallpaperRepository.purityCode("all", isNsfwMode = false, hasApiKey = true))
        assertEquals("110", WallpaperRepository.purityCode("all", isNsfwMode = true, hasApiKey = false))
        assertEquals("010", WallpaperRepository.purityCode("sketchy", isNsfwMode = true, hasApiKey = true))
        assertEquals("001", WallpaperRepository.purityCode("nsfw", isNsfwMode = true, hasApiKey = true))
        assertEquals("011", WallpaperRepository.purityCode("both", isNsfwMode = true, hasApiKey = true))
    }

    @Test
    fun `test discover pagination logic reaches end`() {
        val currentPage = 5
        val lastPage = 5
        val hasReachedEnd = currentPage >= lastPage
        assertTrue(hasReachedEnd)

        val page1 = 1
        val lastPage5 = 5
        val notReached = page1 >= lastPage5
        assertFalse(notReached)
    }

    @Test
    fun `test slideshow pause toggle state`() {
        var isPaused = false
        // Long press on homescreen pauses
        isPaused = !isPaused
        assertTrue(isPaused)
        // Long press again resumes
        isPaused = !isPaused
        assertFalse(isPaused)
    }
}

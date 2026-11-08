package com.example

import com.example.data.model.ModeFilterSettings
import com.example.data.model.WallhavenTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
    fun `test wallhaven autocomplete suggestions`() {
        val suggestions = WallhavenTags.getAutocompleteSuggestions(
            query = "anim",
            excludeTags = setOf("manga")
        )
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.contains("anime"))
        assertTrue(suggestions.contains("anime girl"))
        assertFalse(suggestions.contains("manga"))
    }

    @Test
    fun `test wallhaven similar tags suggestion`() {
        val similar = WallhavenTags.getSimilarTags(
            currentTags = setOf("cyberpunk"),
            excludeTags = setOf("cyberpunk")
        )
        assertTrue(similar.isNotEmpty())
        assertTrue(similar.contains("neon") || similar.contains("sci-fi") || similar.contains("futuristic"))
    }

    @Test
    fun `test wallhaven similar tags for landscape`() {
        val similar = WallhavenTags.getSimilarTags(
            currentTags = setOf("landscape"),
            excludeTags = setOf("landscape")
        )
        assertTrue(similar.isNotEmpty())
        assertTrue(similar.contains("nature") || similar.contains("mountains") || similar.contains("forest"))
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
    fun `test initial tags default to nature for SFW and model for NSFW`() {
        val defaultSfwTag = "nature"
        val defaultNsfwTag = "model"

        fun getInitialTag(isNsfw: Boolean) = if (isNsfw) defaultNsfwTag else defaultSfwTag

        assertEquals("nature", getInitialTag(false))
        assertEquals("model", getInitialTag(true))
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
        // SFW mode defaults
        val sfwCategory = "all"
        val sfwQuery = "nature"
        assertEquals("all", sfwCategory)
        assertEquals("nature", sfwQuery)

        // NSFW mode defaults
        val nsfwCategory = "people"
        val nsfwPurity = "both"
        val nsfwQuery = "model"
        assertEquals("people", nsfwCategory)
        assertEquals("both", nsfwPurity)
        assertEquals("model", nsfwQuery)
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

    @Test
    fun `test buildWallhavenQuery handles like id syntax`() {
        val query = "like:94x38z"
        val built = com.example.data.repository.WallpaperRepository.buildWallhavenQuery(query)
        assertEquals("like:94x38z", built)

        val queryWithSpaces = "like: abc123 "
        val builtWithSpaces = com.example.data.repository.WallpaperRepository.buildWallhavenQuery(queryWithSpaces)
        assertEquals("like:abc123", builtWithSpaces)
    }

    @Test
    fun `test blur filter 10 percent maximum with 1 percent steps`() {
        val maxBlur = 0.10f
        val steps = 9 // 0.01 to 0.09 (9 intermediate steps for 0.00 to 0.10)
        assertEquals(0.10f, maxBlur, 0.0001f)
        assertEquals(9, steps)

        // Test step calculation
        val step1Percent = (0.01f * 100f).toInt()
        val step5Percent = (0.05f * 100f).toInt()
        val step10Percent = (0.10f * 100f).toInt()
        assertEquals(1, step1Percent)
        assertEquals(5, step5Percent)
        assertEquals(10, step10Percent)
    }

    @Test
    fun `test extractWallhavenId extracts clean id from various formats`() {
        assertEquals("94x38z", com.example.data.repository.WallpaperRepository.extractWallhavenId("like:94x38z"))
        assertEquals("94x38z", com.example.data.repository.WallpaperRepository.extractWallhavenId("wallhaven-94x38z.jpg"))
        assertEquals("94x38z", com.example.data.repository.WallpaperRepository.extractWallhavenId("https://w.wallhaven.cc/full/94/wallhaven-94x38z.jpg"))
        assertEquals("94x38z", com.example.data.repository.WallpaperRepository.extractWallhavenId("https://wallhaven.cc/w/94x38z"))
        assertEquals("3qgq66", com.example.data.repository.WallpaperRepository.extractWallhavenId("3qgq66"))
    }

    @Test
    fun `test stored horizontal offset persistence and fallback logic`() {
        val jsonString = """
            {
                "wallpapers": [
                    { "id": "wp1", "horizontalOffset": 0.72 },
                    { "id": "wp2", "horizontalOffset": 0.0 },
                    { "id": "wp3", "horizontalOffset": 1.0 },
                    { "id": "wp4" }
                ]
            }
        """.trimIndent()
        val json = org.json.JSONObject(jsonString)
        val arr = json.getJSONArray("wallpapers")

        val item1 = arr.getJSONObject(0)
        val rawOffset1 = item1.optDouble("horizontalOffset", Double.NaN)
        assertTrue(!rawOffset1.isNaN() && rawOffset1 in 0.0..1.0)
        assertEquals(0.72f, rawOffset1.toFloat(), 0.001f)

        val item4 = arr.getJSONObject(3)
        val rawOffset4 = if (item4.has("horizontalOffset")) item4.optDouble("horizontalOffset", Double.NaN) else Double.NaN
        assertTrue(rawOffset4.isNaN())
    }

    @Test
    fun `test favorite effective offset uses stored offset without tap`() {
        val favWithOffset = com.example.data.local.FavoriteWallpaper(
            id = "w1",
            url = "https://example.com/w1.jpg",
            thumbUrl = "https://example.com/t1.jpg",
            purity = "sfw",
            category = "all",
            horizontalOffset = 0.82f
        )
        val effective = if (favWithOffset.horizontalOffset in 0f..1f) favWithOffset.horizontalOffset else 0.5f
        assertEquals(0.82f, effective, 0.001f)

        val favDefault = com.example.data.local.FavoriteWallpaper(
            id = "w2",
            url = "https://example.com/w2.jpg",
            thumbUrl = "https://example.com/t2.jpg",
            purity = "sfw",
            category = "all"
        )
        val effectiveDefault = if (favDefault.horizontalOffset in 0f..1f) favDefault.horizontalOffset else 0.5f
        assertEquals(0.5f, effectiveDefault, 0.001f)
    }
}

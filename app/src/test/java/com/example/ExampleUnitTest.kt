package com.example

import com.example.data.model.ModeFilterSettings
import com.example.data.model.WallhavenTags
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
}

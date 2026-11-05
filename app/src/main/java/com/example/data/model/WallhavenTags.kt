package com.example.data.model

object WallhavenTags {

    val popularTags: List<String> = listOf(
        "anime",
        "anime girl",
        "manga",
        "waifu",
        "illustration",
        "digital art",
        "concept art",
        "artwork",
        "fantasy",
        "cyberpunk",
        "sci-fi",
        "science fiction",
        "futuristic",
        "neon",
        "city",
        "cityscape",
        "urban",
        "night",
        "nature",
        "landscape",
        "mountains",
        "forest",
        "trees",
        "ocean",
        "sea",
        "beach",
        "sunset",
        "sunrise",
        "sky",
        "clouds",
        "space",
        "galaxy",
        "nebula",
        "stars",
        "planet",
        "cosmos",
        "astronomy",
        "cars",
        "sports car",
        "supercar",
        "vehicle",
        "aircraft",
        "military",
        "weapon",
        "soldier",
        "monochrome",
        "black background",
        "minimalism",
        "dark",
        "simple",
        "abstract",
        "3d",
        "cgi",
        "render",
        "portrait",
        "women",
        "girl",
        "model",
        "cosplay",
        "brunette",
        "blonde",
        "red hair",
        "blue eyes",
        "smiling",
        "looking at viewer",
        "video games",
        "retro",
        "80s",
        "synthwave",
        "retrowave",
        "vaporwave",
        "pixel art",
        "lofi",
        "aesthetic",
        "cat",
        "dog",
        "wildlife",
        "animals",
        "flowers",
        "snow",
        "rain",
        "water",
        "lake",
        "architecture",
        "samurai",
        "warrior",
        "robot",
        "cyborg",
        "mecha",
        "swimsuit",
        "lingerie",
        "bikini",
        "sensual",
        "boudoir",
        "ecchi"
    )

    private val similarityMap: Map<String, List<String>> = mapOf(
        "anime" to listOf("anime girl", "manga", "waifu", "illustration", "digital art", "pixel art"),
        "anime girl" to listOf("anime", "waifu", "looking at viewer", "illustration", "brunette", "blonde"),
        "cyberpunk" to listOf("sci-fi", "futuristic", "neon", "cityscape", "night", "cyborg", "synthwave"),
        "landscape" to listOf("nature", "mountains", "forest", "lake", "sunset", "sky", "scenery"),
        "nature" to listOf("landscape", "trees", "forest", "mountains", "wildlife", "flowers", "lake"),
        "fantasy" to listOf("concept art", "digital art", "warrior", "magic", "artwork", "dragon"),
        "digital art" to listOf("artwork", "illustration", "concept art", "fantasy", "render"),
        "portrait" to listOf("women", "model", "girl", "brunette", "eyes", "photography"),
        "cars" to listOf("supercar", "sports car", "vehicle", "porsche", "bmw", "drift"),
        "space" to listOf("galaxy", "nebula", "stars", "planet", "astronomy", "cosmos", "sci-fi"),
        "neon" to listOf("cyberpunk", "night", "city", "synthwave", "glow", "vaporwave"),
        "synthwave" to listOf("retrowave", "vaporwave", "neon", "80s", "retro", "aesthetic"),
        "minimalism" to listOf("simple", "clean", "dark", "monochrome", "black background"),
        "dark" to listOf("black background", "shadow", "night", "monochrome", "minimalism"),
        "sunset" to listOf("sunrise", "sky", "clouds", "dusk", "golden hour", "landscape"),
        "ocean" to listOf("sea", "beach", "water", "waves", "coast", "nature"),
        "cosplay" to listOf("costume", "model", "women", "anime", "video games"),
        "video games" to listOf("gamer", "character", "fan art", "cyberpunk", "concept art"),
        "city" to listOf("cityscape", "urban", "night", "architecture", "street", "neon"),
        "forest" to listOf("trees", "woods", "nature", "landscape", "fog", "foliage"),
        "mountains" to listOf("snow", "alps", "peaks", "landscape", "nature"),
        "clouds" to listOf("sky", "sunset", "atmosphere", "blue sky", "landscape"),
        "architecture" to listOf("building", "interior", "structure", "city", "modern"),
        "animals" to listOf("wildlife", "cat", "dog", "birds", "nature"),
        "cat" to listOf("kitten", "cute", "animals", "pet"),
        "women" to listOf("girl", "model", "portrait", "brunette", "blonde"),
        "girl" to listOf("women", "model", "portrait", "looking at viewer"),
        "model" to listOf("women", "portrait", "photoshoot", "sensual", "cosplay"),
        "sensual" to listOf("boudoir", "lingerie", "swimsuit", "model", "bikini"),
        "bikini" to listOf("swimsuit", "beach", "model", "sensual", "summer"),
        "swimsuit" to listOf("bikini", "beach", "model", "sensual", "pool"),
        "lingerie" to listOf("boudoir", "sensual", "model", "women")
    )

    fun getAutocompleteSuggestions(query: String, excludeTags: Set<String>, limit: Int = 8): List<String> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return emptyList()

        val prefixMatches = popularTags.filter {
            it.lowercase().startsWith(clean) && !excludeTags.contains(it.lowercase())
        }
        val containsMatches = popularTags.filter {
            !it.lowercase().startsWith(clean) && it.lowercase().contains(clean) && !excludeTags.contains(it.lowercase())
        }

        return (prefixMatches + containsMatches).distinct().take(limit)
    }

    fun getSimilarTags(currentTags: Set<String>, excludeTags: Set<String>, limit: Int = 8): List<String> {
        val candidates = mutableListOf<String>()

        for (tag in currentTags) {
            val lower = tag.trim().lowercase()
            similarityMap[lower]?.let { candidates.addAll(it) }

            // Partial match in map
            if (!similarityMap.containsKey(lower)) {
                for ((key, list) in similarityMap) {
                    if (lower.contains(key) || key.contains(lower)) {
                        candidates.addAll(list)
                    }
                }
            }
        }

        val filtered = candidates
            .map { it.lowercase() }
            .filter { it !in excludeTags && it !in currentTags }
            .distinct()

        return if (filtered.isNotEmpty()) {
            filtered.take(limit)
        } else {
            popularTags
                .map { it.lowercase() }
                .filter { it !in excludeTags && it !in currentTags }
                .take(limit)
        }
    }
}

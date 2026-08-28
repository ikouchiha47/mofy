package com.mofy.app.search

class RuleBasedFacetDecoder : FacetDecoder {

    override fun decode(query: String): FacetResult {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return FacetResult()

        val genres = extractGenres(q)
        val excludedGenres = extractExcludedGenres(q, genres)
        return FacetResult(
            genres = genres,
            excludedGenres = excludedGenres,
            hasDate = DATE_RE.containsMatchIn(q),
            hasRuntime = RUNTIME_RE.containsMatchIn(q),
            hasRating = RATING_KW.any { q.contains(it) },
            hasMood = MOOD_KW.any { q.contains(it) },
            hasName = hasProperName(query),
            hasOther = OTHER_KW.any { q.contains(it) },
            popularity = popularity(q),
        )
    }

    private fun extractGenres(q: String): List<String> =
        GENRES.entries.mapNotNull { (kw, tag) -> tag.takeIf { q.contains(kw) } }.distinct()

    // Mirrors Python _extract_negated_genres: walk tokens left-to-right;
    // activate negation on trigger words, deactivate on "but"; any genre word
    // while negation is active → excluded. Only operates on the model's own
    // detected genres (incl_genres), so an excluded genre must also appear
    // unambiguously in the query as a genre word.
    private fun extractExcludedGenres(q: String, inclGenres: List<String>): List<String> {
        val tokens = q.split(Regex("\\s+"))
        val excluded = mutableListOf<String>()
        var negated = false
        for (token in tokens) {
            if (token in NEGATION_STOP) { negated = false; continue }
            if (token in NEGATION_TRIGGERS) { negated = true; continue }
            if (negated) {
                val matched = GENRES[token] ?: GENRES.entries.firstOrNull { token.startsWith(it.key) }?.value
                if (matched != null && matched in inclGenres) excluded += matched
            }
        }
        return excluded.distinct()
    }

    private fun popularity(q: String): String = when {
        NICHE_KW.any { q.contains(it) } -> "niche"
        MAINSTREAM_KW.any { q.contains(it) } -> "mainstream"
        else -> "none"
    }

    // Heuristic: consecutive title-case words not at query start and not in
    // the common-word exclusion list → likely a name.
    private fun hasProperName(raw: String): Boolean {
        val words = raw.trim().split(Regex("\\s+"))
        return words.drop(1).any { w ->
            w.length >= 2 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }
                && w.lowercase() !in COMMON_WORDS
        }
    }

    companion object {
        private val GENRES = mapOf(
            "drama" to "Drama", "dramas" to "Drama",
            "comedy" to "Comedy", "comedies" to "Comedy",
            "action" to "Action",
            "thriller" to "Thriller", "thrillers" to "Thriller",
            "horror" to "Horror",
            "sci-fi" to "Sci-Fi", "scifi" to "Sci-Fi", "science fiction" to "Sci-Fi",
            "romance" to "Romance", "romantic" to "Romance",
            "documentary" to "Documentary", "documentaries" to "Documentary",
            "animation" to "Animation", "animated" to "Animation",
            "crime" to "Crime",
            "mystery" to "Mystery",
            "fantasy" to "Fantasy",
            "adventure" to "Adventure",
            "western" to "Western",
            "history" to "History", "historical" to "History",
            "biography" to "Biography", "biopic" to "Biography",
            "war" to "War",
            "sport" to "Sport", "sports" to "Sport",
            "music" to "Music", "musical" to "Music",
            "family" to "Family",
        )

        private val NEGATION_TRIGGERS = setOf("no", "not", "without", "except", "excluding")
        private val NEGATION_STOP = setOf("but", "however", "although", "though")

        private val DATE_RE = Regex("""(?:(?:19|20)\d{2}s?|\d{2}s|(?:80|90|70|60|50)s)""")
        private val RUNTIME_RE = Regex(
            """(?:short|brief|long|under|over|around|about)\s*(?:\d+\s*(?:min|minute|hour|hr)s?)?|""" +
                """\d+\s*(?:min|minute|hour|hr)s?|two[\s-]hour|feature[\s-]length""",
        )

        private val RATING_KW = setOf(
            "must-watch", "must watch", "acclaimed", "masterpiece", "classic",
            "award", "critically", "must see", "must-see",
        )
        private val MOOD_KW = setOf(
            "cozy", "feel-good", "feel good", "dark", "brooding", "uplifting",
            "atmospheric", "intense", "heartwarming", "gritty", "scary",
            "suspenseful", "nostalgic", "epic", "rainy day", "lighthearted",
            "light-hearted", "cheerful", "bleak", "melancholic", "funny",
            "sad", "tense", "chilling",
        )
        private val NICHE_KW = setOf(
            "hidden gem", "underrated", "obscure", "nobody talks about",
            "overlooked", "forgotten", "under the radar", "cult classic",
        )
        private val MAINSTREAM_KW = setOf(
            "blockbuster", "box office", "mainstream", "popular", "hit",
            "famous", "well-known", "big budget",
        )
        private val OTHER_KW = setOf(
            "french", "korean", "japanese", "italian", "german", "spanish",
            "foreign", "foreign language", "new wave", "set in",
            "4k", "bluray", "blu-ray", "hdr", "black and white", "silent",
            "dubbed", "subtitled",
        )
        private val COMMON_WORDS = setOf(
            "the", "a", "an", "in", "on", "at", "to", "of", "and", "or", "but",
            "for", "with", "from", "that", "this", "is", "are", "was", "were",
            "be", "been", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "some", "any",
            "more", "most", "just", "like", "about", "after",
        )
    }
}

package com.mofy.app.search

class DefaultGenreScoreDeriver : GenreScoreDeriver {
    override fun deriveGenreScores(
        matches: List<VectorMatch>,
        catalog: Map<String, VectorEntry>,
    ): List<GenreScore> {
        if (matches.isEmpty()) return emptyList()
        val raw = mutableMapOf<String, Double>()
        for (match in matches) {
            val genres = catalog[match.id]?.genreTags ?: continue
            for (genre in genres) raw[genre] = (raw[genre] ?: 0.0) + match.score
        }
        val max = raw.values.maxOrNull() ?: return emptyList()
        if (max == 0.0) return raw.keys.map { GenreScore(it, 0.0) }
        return raw.map { (genre, sum) -> GenreScore(genre, sum / max) }
    }
}

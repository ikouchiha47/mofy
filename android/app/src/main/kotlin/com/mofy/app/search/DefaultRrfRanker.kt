package com.mofy.app.search

class DefaultRrfRanker : RrfRanker {
    override fun fuse(
        embeddingRanks: Map<String, Int>,
        keywordRanks: Map<String, Int>,
        genreRanks: Map<String, Int>,
        k: Int,
    ): List<String> {
        val ids = (embeddingRanks.keys + keywordRanks.keys + genreRanks.keys).toSet()
        return ids
            .map { id ->
                var score = 0.0
                embeddingRanks[id]?.let { score += 1.0 / (k + it) }
                keywordRanks[id]?.let { score += 1.0 / (k + it) }
                genreRanks[id]?.let { score += 1.0 / (k + it) }
                id to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}

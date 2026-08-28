package com.mofy.app.search

// ---- Typo corrector strategy ---------------------------------------------

/** Returns true if `candidate` (a corpus word) is "close enough" to `term` (a query token). */
fun interface TypoMatcher {
    fun matches(term: String, candidate: String): Boolean
}

// ---- Track A: keyword search ----------------------------------------------

data class SearchableItem(val id: String, val title: String, val overview: String)

data class SearchResult(val id: String, val score: Double)

fun interface LibrarySearchQuery {
    fun search(query: String, items: List<SearchableItem>): List<SearchResult>
}

// ---- Track B: vector retrieval --------------------------------------------

data class VectorEntry(
    val id: String,
    val vector: FloatArray,
    val genreTags: List<String> = emptyList(),
)

data class VectorMatch(val id: String, val score: Double)

interface VectorIndex {
    fun insert(entry: VectorEntry)
    fun queryNearest(queryVector: FloatArray, k: Int): List<VectorMatch>
}

data class GenreScore(val genre: String, val score: Double)

fun interface GenreScoreDeriver {
    fun deriveGenreScores(matches: List<VectorMatch>, catalog: Map<String, VectorEntry>): List<GenreScore>
}

// ---- B5: mood/plot resolution ---------------------------------------------

fun interface MoodPlotQueryResolver {
    fun resolve(freeTextQuery: String, k: Int): List<VectorMatch>
}

// ---- B6: reciprocal rank fusion -------------------------------------------

fun interface RrfRanker {
    fun fuse(
        embeddingRanks: Map<String, Int>,
        keywordRanks: Map<String, Int>,
        genreRanks: Map<String, Int>,
        k: Int,
    ): List<String>
}

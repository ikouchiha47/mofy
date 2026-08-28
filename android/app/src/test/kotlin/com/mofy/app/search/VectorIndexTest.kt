package com.mofy.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Track B1/B4 (docs/tasks/09-recommendation-engine.md, ADR 0007): vec0-style
 * nearest-neighbor retrieval and per-genre score derivation.
 *
 * NOT tested here (out of scope for this pure-JVM/black-box suite):
 * - The real `vec0` virtual table, native `sqlite-vec` extension loading, or
 *   SQL `MATCH`/distance-order query syntax — those are integration-level
 *   (ADR 0007) and require the actual native driver; this suite only pins
 *   the retrieval/scoring contract (`VectorIndex`, `GenreScoreDeriver`)
 *   against tiny hand-constructed vectors with known geometry.
 * - 768-dim real EmbeddingGemma output — fixtures here are 2-3 dimensional,
 *   chosen so cosine similarity can be hand-verified, not representative of
 *   real embedding magnitude/dimensionality.
 *
 * `InMemoryVectorIndex` and `DefaultGenreScoreDeriver` do not exist yet —
 * this file will not compile until they're implemented against the
 * `VectorIndex`/`GenreScoreDeriver` contracts in SearchFixtures.kt.
 */
class VectorIndexTest {

    @Test
    fun `nearest neighbor query ranks identical vector above orthogonal above opposite`() {
        val index: VectorIndex = InMemoryVectorIndex()
        // query = [1, 0, 0]
        // item1 = [1, 0, 0]  -> cosine = (1*1+0*0+0*0) / (1*1) = 1.0  (identical direction)
        // item2 = [0, 1, 0]  -> cosine = (1*0+0*1+0*0) / (1*1) = 0.0  (orthogonal)
        // item3 = [-1, 0, 0] -> cosine = (1*-1+0*0+0*0) / (1*1) = -1.0 (opposite direction)
        // item4 = [1, 1, 0]  -> cosine = (1*1+0*1+0*0) / (1 * sqrt(2)) = 1/1.41421356 ≈ 0.70710678
        index.insert(VectorEntry(id = "item1", vector = floatArrayOf(1f, 0f, 0f)))
        index.insert(VectorEntry(id = "item2", vector = floatArrayOf(0f, 1f, 0f)))
        index.insert(VectorEntry(id = "item3", vector = floatArrayOf(-1f, 0f, 0f)))
        index.insert(VectorEntry(id = "item4", vector = floatArrayOf(1f, 1f, 0f)))

        val results = index.queryNearest(floatArrayOf(1f, 0f, 0f), k = 3)

        assertEquals(3, results.size)
        assertEquals(listOf("item1", "item4", "item2"), results.map { it.id })
        assertTrue(abs(results[0].score - 1.0) < 1e-6, "item1 score: ${results[0].score}")
        assertTrue(abs(results[1].score - 0.70710678) < 1e-4, "item4 score: ${results[1].score}")
        assertTrue(abs(results[2].score - 0.0) < 1e-6, "item2 score: ${results[2].score}")
        assertTrue(results.none { it.id == "item3" }, "opposite-direction vector should not be in top-3 of 4")
    }

    @Test
    fun `k limits result count even when more entries exist`() {
        val index: VectorIndex = InMemoryVectorIndex()
        index.insert(VectorEntry(id = "a", vector = floatArrayOf(1f, 0f)))
        index.insert(VectorEntry(id = "b", vector = floatArrayOf(0.9f, 0.1f)))
        index.insert(VectorEntry(id = "c", vector = floatArrayOf(0f, 1f)))

        val results = index.queryNearest(floatArrayOf(1f, 0f), k = 1)

        assertEquals(1, results.size)
        assertEquals("a", results.single().id)
    }

    @Test
    fun `per-genre score derivation aggregates and normalizes similarity across retrieved set`() {
        // query = [1, 0, 0]
        // id1 genres=[Action]           vector=[1, 0, 0]     -> cosine = 1.0 (unit vector, identical)
        // id2 genres=[Action, Thriller] vector=[0.8, 0.6, 0] -> |v| = sqrt(0.8^2+0.6^2) = sqrt(1.0) = 1.0
        //                                                        cosine = (1*0.8) / (1*1) = 0.8
        // id3 genres=[Comedy]           vector=[0, 1, 0]     -> cosine = 0.0
        val catalog = mapOf(
            "id1" to VectorEntry(id = "id1", vector = floatArrayOf(1f, 0f, 0f), genreTags = listOf("Action")),
            "id2" to VectorEntry(id = "id2", vector = floatArrayOf(0.8f, 0.6f, 0f), genreTags = listOf("Action", "Thriller")),
            "id3" to VectorEntry(id = "id3", vector = floatArrayOf(0f, 1f, 0f), genreTags = listOf("Comedy")),
        )
        // Retrieved top-3 set with hand-computed cosine scores from above:
        val matches = listOf(
            VectorMatch(id = "id1", score = 1.0),
            VectorMatch(id = "id2", score = 0.8),
            VectorMatch(id = "id3", score = 0.0),
        )

        // Hand computation:
        // raw sum per genre (sum of similarity scores of items carrying that tag):
        //   Action:   id1(1.0) + id2(0.8) = 1.8
        //   Thriller: id2(0.8)            = 0.8
        //   Comedy:   id3(0.0)            = 0.0
        // normalized by dividing by the max raw sum (1.8):
        //   Action:   1.8 / 1.8 = 1.0
        //   Thriller: 0.8 / 1.8 = 0.44444...
        //   Comedy:   0.0 / 1.8 = 0.0
        val deriver: GenreScoreDeriver = DefaultGenreScoreDeriver()
        val genreScores = deriver.deriveGenreScores(matches, catalog).associate { it.genre to it.score }

        assertEquals(3, genreScores.size)
        assertTrue(abs((genreScores["Action"] ?: -1.0) - 1.0) < 1e-6, "Action: ${genreScores["Action"]}")
        assertTrue(abs((genreScores["Thriller"] ?: -1.0) - 0.44444) < 1e-4, "Thriller: ${genreScores["Thriller"]}")
        assertTrue(abs((genreScores["Comedy"] ?: -1.0) - 0.0) < 1e-6, "Comedy: ${genreScores["Comedy"]}")
    }

    @Test
    fun `genre absent from retrieved set does not appear in derived scores`() {
        val catalog = mapOf(
            "id1" to VectorEntry(id = "id1", vector = floatArrayOf(1f, 0f), genreTags = listOf("Horror")),
        )
        val matches = listOf(VectorMatch(id = "id1", score = 0.5))

        val deriver: GenreScoreDeriver = DefaultGenreScoreDeriver()
        val genreScores = deriver.deriveGenreScores(matches, catalog)

        assertEquals(listOf("Horror"), genreScores.map { it.genre })
        assertTrue(genreScores.none { it.genre == "SciFi" })
    }

    @Test
    fun `querying an empty index returns no matches`() {
        val index: VectorIndex = InMemoryVectorIndex()

        val results = index.queryNearest(floatArrayOf(1f, 0f), k = 5)

        assertTrue(results.isEmpty(), "expected no matches from an empty index, got: $results")
    }

    @Test
    fun `deriving genre scores from an empty match set returns no genres`() {
        val deriver: GenreScoreDeriver = DefaultGenreScoreDeriver()

        val genreScores = deriver.deriveGenreScores(matches = emptyList(), catalog = emptyMap())

        assertTrue(genreScores.isEmpty(), "expected no genre scores from an empty match set, got: $genreScores")
    }
}

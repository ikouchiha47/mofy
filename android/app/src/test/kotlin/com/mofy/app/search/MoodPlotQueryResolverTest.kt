package com.mofy.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Track B5 (docs/tasks/09-recommendation-engine.md, ADR 0002): mood queries
 * ("feeling nostalgic") and plot queries ("a detective investigates...")
 * SHALL resolve through the same mechanism — no mode/type parameter
 * distinguishing them.
 *
 * NOT tested here (out of scope for this pure-JVM/black-box suite):
 * - The real EmbeddingGemma/LiteRT embedding step — see
 *   OnDeviceEmbeddingContractTest.kt for why that's deferred to instrumented
 *   testing. This suite uses a hand-written `FakeQueryEmbedder` that maps
 *   known query strings to known fixture vectors, so retrieval logic can be
 *   tested deterministically without a real model.
 * - Genre-score derivation itself (covered by VectorIndexTest) — this file
 *   only proves the mood/plot unification, i.e. that `resolve()` is a single
 *   call shape used identically for both kinds of query text.
 *
 * `VectorMoodPlotQueryResolver` does not exist yet — this file will not
 * compile until it's implemented against `MoodPlotQueryResolver` in
 * SearchFixtures.kt.
 */
class MoodPlotQueryResolverTest {

    /** Fake embedder: maps known query strings to known fixture vectors — no real model involved. */
    private class FakeQueryEmbedder(private val fixedVectors: Map<String, FloatArray>) {
        fun embed(text: String): FloatArray =
            fixedVectors[text] ?: error("no fixture vector registered for query: $text")
    }

    @Test
    fun `mood query and plot query both resolve via the exact same resolve call shape`() {
        val index: VectorIndex = InMemoryVectorIndex()
        index.insert(VectorEntry(id = "cozy-drama", vector = floatArrayOf(1f, 0f), genreTags = listOf("Drama")))
        index.insert(VectorEntry(id = "action-flick", vector = floatArrayOf(0f, 1f), genreTags = listOf("Action")))

        val embedder = FakeQueryEmbedder(
            fixedVectors = mapOf(
                "feeling nostalgic" to floatArrayOf(1f, 0f),
                "a detective investigates disappearances in a coastal town" to floatArrayOf(0f, 1f),
            ),
        )

        // Same resolver instance, same `resolve(query, k)` method for both —
        // no separate "resolveMood" vs "resolvePlot" entry point exists on
        // the MoodPlotQueryResolver contract (see SearchFixtures.kt).
        val resolver: MoodPlotQueryResolver = VectorMoodPlotQueryResolver(embedder::embed, index)

        val moodResults = resolver.resolve("feeling nostalgic", k = 2)
        val plotResults = resolver.resolve(
            "a detective investigates disappearances in a coastal town",
            k = 2,
        )

        // Mood query's fixture vector is closest to "cozy-drama" -> that
        // must be the top result, proving the mood string actually drove
        // retrieval rather than returning some fixed/default ordering.
        assertTrue(moodResults.isNotEmpty())
        assertEquals("cozy-drama", moodResults.first().id)

        // Plot query's fixture vector is closest to "action-flick" -> a
        // *different* top result than the mood query, proving both queries
        // were independently embedded and retrieved through the identical
        // resolve() path rather than one hardcoded response.
        assertTrue(plotResults.isNotEmpty())
        assertEquals("action-flick", plotResults.first().id)
    }

    @Test
    fun `resolver surfaces the aligned match and filters out the opposed one`() {
        // Two catalog items: one whose vector aligns with the query
        // (cosine +1.0, a genuine match) and one in the exact opposite
        // direction (cosine -1.0, must not surface as a positive match).
        // A stub that always returns emptyList() fails here because
        // "aligned-match" is required to be present; a stub that returns
        // everything unfiltered fails because "opposed-match" is required
        // to be absent. Neither failure mode is possible without the
        // resolver actually running retrieval + a similarity filter.
        val index: VectorIndex = InMemoryVectorIndex()
        index.insert(VectorEntry(id = "aligned-match", vector = floatArrayOf(1f, 0f)))
        index.insert(VectorEntry(id = "opposed-match", vector = floatArrayOf(-1f, 0f)))

        val embedder = FakeQueryEmbedder(
            fixedVectors = mapOf("some query" to floatArrayOf(1f, 0f)),
        )
        val resolver: MoodPlotQueryResolver = VectorMoodPlotQueryResolver(embedder::embed, index)

        val results = resolver.resolve("some query", k = 5)

        assertTrue(results.any { it.id == "aligned-match" && it.score > 0.0 })
        assertTrue(results.none { it.id == "opposed-match" })
    }

    @Test
    fun `resolving against an empty catalog returns no matches`() {
        val index: VectorIndex = InMemoryVectorIndex()
        val embedder = FakeQueryEmbedder(fixedVectors = mapOf("anything" to floatArrayOf(1f, 0f)))
        val resolver: MoodPlotQueryResolver = VectorMoodPlotQueryResolver(embedder::embed, index)

        val results = resolver.resolve("anything", k = 5)

        assertTrue(results.isEmpty(), "expected no matches against an empty catalog, got: $results")
    }
}

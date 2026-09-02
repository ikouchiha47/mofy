package com.mofy.app.data.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for matchQueryFromTokens's stopword handling - the
 * end-to-end instrumented test (CatalogRepositorySearchTest) deliberately
 * runs without a live embedder (no model download in a test), so it can
 * only exercise the FTS/exact-title tracks, not the embedding track that
 * compensates for FTS misses on long natural-language queries in
 * production. Stopword filtering itself is a pure function - test it
 * directly here rather than through a device-dependent, embedder-starved
 * end-to-end path.
 */
class LibrarySearchQueryStopwordTest {

    @Test
    fun `common stopwords are dropped from a multi-word query`() {
        // "the" contributes nothing to an AND query once "dark"/"knight"
        // are present - dropping it narrows the match, doesn't broaden it.
        assertEquals("dark* knight*", buildFtsMatchQuery("the dark knight"))
    }

    @Test
    fun `a query that is only a stopword is not stripped to nothing`() {
        // "It" (2017) - a real title that is itself a stopword. The
        // ifEmpty guard in matchQueryFromTokens must keep it rather than
        // discard the only token and return an empty/null query.
        assertEquals("it*", buildFtsMatchQuery("it"))
    }

    @Test
    fun `a title that is mostly a stopword keeps its real content word`() {
        assertEquals("room*", buildFtsMatchQuery("the room"))
    }

    @Test
    fun `blank query still returns null`() {
        assertEquals(null, buildFtsMatchQuery("   "))
    }
}

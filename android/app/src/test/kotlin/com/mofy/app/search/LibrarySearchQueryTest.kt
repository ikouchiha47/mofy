package com.mofy.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Track A (docs/tasks/09-recommendation-engine.md): keyword search
 * improvements over the existing FTS4 `library_search` table — prefix
 * matching, typo tolerance, multi-word queries, relevance ranking.
 *
 * NOT tested here (out of scope for this pure-JVM/black-box suite):
 * - Real FTS4/SQLite `MATCH` query syntax or `matchinfo`/BM25 internals —
 *   those are exercised against the real `LibraryDao`/`LibrarySearchEntity`
 *   in an instrumented/Room test, not here. This suite only pins the
 *   *ranking contract* (`LibrarySearchQuery`) against plain in-memory
 *   fixtures.
 * - Stemming/synonyms — not part of the Track A task list.
 * Typo tolerance uses SymSpell delete-only indexing (delete distance ≤ 2),
 * which catches fat-finger, key-slide, and transposition errors without a
 * keyboard adjacency map.
 *
 * `DefaultLibrarySearchQuery` (referenced below) does not exist yet — this
 * file will not compile until it's implemented. That's intentional: this is
 * a spec-first suite against `LibrarySearchQuery` as defined in
 * SearchFixtures.kt.
 */
class LibrarySearchQueryTest {

    private val library = listOf(
        SearchableItem(
            id = "1",
            title = "The Coastal Detective",
            overview = "a detective investigates disappearances in a coastal town",
        ),
        SearchableItem(
            id = "2",
            title = "Detective Bay",
            overview = "a retired cop returns to solve one final case",
        ),
        SearchableItem(
            id = "3",
            title = "Clone Sisters",
            overview = "a scientist swaps bodies with her clone",
        ),
        SearchableItem(
            id = "4",
            title = "Nostalgia Drive",
            overview = "a road trip movie about reconnecting with old friends",
        ),
    )

    private val engine: LibrarySearchQuery = DefaultLibrarySearchQuery()

    @Test
    fun `exact title match ranks above partial word match`() {
        val results = engine.search("Detective Bay", library)

        assertTrue(results.isNotEmpty())
        // "Detective Bay" (id 2) is an exact title match; "The Coastal
        // Detective" (id 1) only partially matches on "Detective" — exact
        // should outrank partial, not merely both appear.
        assertEquals("2", results.first().id)
        assertTrue(results.any { it.id == "1" })
        val rank2 = results.indexOfFirst { it.id == "2" }
        val rank1 = results.indexOfFirst { it.id == "1" }
        assertTrue(rank2 < rank1)
    }

    @Test
    fun `prefix query surfaces result before full word is typed`() {
        // "nosta" is a genuine prefix of "Nostalgia" — incremental typing
        // should surface id 4 without the full word having been typed yet.
        val results = engine.search("nosta", library)

        assertTrue(results.any { it.id == "4" })
    }

    @Test
    fun `single character typo still surfaces the intended result`() {
        // "detectvie" is "detective" with two adjacent letters swapped —
        // a single-edit (transposition) typo.
        val results = engine.search("detectvie", library)

        assertTrue(
            results.any { it.id == "1" || it.id == "2" },
            "expected typo-tolerant match against a detective title, got: $results",
        )
    }

    @Test
    fun `multi-word query matching both words ranks above matching only one`() {
        val results = engine.search("clone bodies", library)

        assertTrue(results.isNotEmpty())
        // id 3's overview contains both "clone" and "bodies"; no other
        // fixture item contains either word, so id 3 must be first and the
        // only real match — not just "present somewhere in a long list".
        assertEquals("3", results.first().id)
    }

    @Test
    fun `query with no plausible match returns empty rather than everything`() {
        val results = engine.search("xyzzyquux industrial thermostat", library)

        assertTrue(results.isEmpty(), "expected no matches, got: $results")
    }

    @Test
    fun `results are ordered by descending score`() {
        val results = engine.search("detective", library)

        assertTrue(results.size >= 2)
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].score >= results[i + 1].score,
                "results not sorted by descending score: $results",
            )
        }
    }

    @Test
    fun `blank query returns no matches rather than the whole library`() {
        val results = engine.search("", library)

        assertTrue(results.isEmpty(), "expected no matches for a blank query, got: $results")
    }

    @Test
    fun `searching an empty library returns no matches`() {
        val results = engine.search("detective", items = emptyList())

        assertTrue(results.isEmpty(), "expected no matches against an empty library, got: $results")
    }
}

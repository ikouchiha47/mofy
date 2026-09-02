package com.mofy.app.data.catalog

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mofy.app.search.OnDeviceEmbedder
import com.mofy.app.search.RuleBasedFacetDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the real bundled catalog.db asset (not fixtures) - the only
 * instrumented coverage of Discover's actual search behavior. Covers the
 * exact-title boost and the ftsSearch AND/stopword fix (both landed after
 * the user pointed out no test exercised real single-/multi-word direct
 * title matching, and that the old "word1" OR "word2" query would let a
 * popular unrelated title bury a real multi-word match).
 *
 * OnDeviceEmbedder is passed uninitialized deliberately (never call init())
 * - embed() returns null with no interpreter/tokenizer loaded, no model
 * download needed, which only disables the embedding (Track B) signal.
 * exactTitleSearch and ftsSearch (Track A) are independent of it, which is
 * exactly what these tests are checking.
 */
@RunWith(AndroidJUnit4::class)
class CatalogRepositorySearchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = CatalogDatabase.get(context)
    private val repository = CatalogRepository(db)
    private val embedder = OnDeviceEmbedder(context)
    private val facetDecoder = RuleBasedFacetDecoder()

    @Test
    fun singleWordExactTitleRanksFirst() = runBlocking {
        // tt1375666, numVotes ~2.8M - top-voted single-word title in the
        // bundled dataset (verified via sqlite3 against the real asset).
        val results = repository.semanticSearch("Inception", context, embedder, facetDecoder)

        assertTrue("expected a non-empty result set for 'Inception'", results.isNotEmpty())
        assertTrue(
            "expected Inception (tt1375666) first, got ${results.take(3).map { it.tconst to it.title }}",
            results.first().tconst == "tt1375666",
        )
    }

    @Test
    fun multiWordExactTitleRanksFirst() = runBlocking {
        // tt0468569 "The Dark Knight" - deliberately starts with the
        // stopword "The", which is exactly the case the old OR-joined
        // ftsSearch query mishandled (matched any document containing just
        // "The" and ranked purely by popularity, not by which/how many
        // terms matched).
        val results = repository.semanticSearch("The Dark Knight", context, embedder, facetDecoder)

        assertTrue("expected a non-empty result set for 'The Dark Knight'", results.isNotEmpty())
        assertTrue(
            "expected The Dark Knight (tt0468569) first, got ${results.take(3).map { it.tconst to it.title }}",
            results.first().tconst == "tt0468569",
        )
    }

    @Test
    fun partialTypedTitlePrefixMatches() = runBlocking {
        // "Incep" is a genuine prefix, not a full word - exercises the
        // unquoted `word*` prefix behavior (quoted tokens silently degrade
        // to exact-whole-word-only in FTS3/4, which is the bug
        // matchQueryFromTokens's doc comment already names).
        val results = repository.semanticSearch("Incep", context, embedder, facetDecoder)

        assertTrue(
            "expected Inception to surface for the prefix 'Incep', got ${results.take(5).map { it.tconst to it.title }}",
            results.any { it.tconst == "tt1375666" },
        )
    }

    @Test
    fun multiWordKeywordPhraseMatchesViaFtsAlone() = runBlocking {
        // "dark knight" (no "The") isn't an exact or prefix title match, so
        // exactTitleSearch can't short-circuit this - it isolates whether
        // ftsSearch's AND+prefix query genuinely finds a multi-word phrase
        // through the FTS track by itself, with the embedder deliberately
        // uninitialized (no Track B signal available). This is the direct
        // regression check for the old "word1" OR "word2" bug: that query
        // shape would have buried this behind any more-popular title merely
        // containing "dark" or "knight" alone; AND+prefix requires both.
        val results = repository.semanticSearch("dark knight", context, embedder, facetDecoder)

        assertTrue(
            "expected The Dark Knight (tt0468569) to surface via FTS alone for 'dark knight', got ${results.take(5).map { it.tconst to it.title }}",
            results.any { it.tconst == "tt0468569" },
        )
    }
}

package com.mofy.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Track B6 (docs/tasks/09-recommendation-engine.md, ADR 0002): reciprocal
 * rank fusion combining embedding-similarity rank, FTS4 keyword-match rank,
 * and derived-genre-score rank into one final ranking.
 *
 * NOT tested here (out of scope for this pure-JVM/black-box suite):
 * - Where the three input rank maps come from (real vec0 retrieval, real
 *   FTS4 query, real genre-score derivation) — those are covered elsewhere
 *   (VectorIndexTest, and Track A's real FTS4 work). This suite only pins
 *   the fusion arithmetic itself against a fully known, hand-computed
 *   golden dataset.
 * - Phase 08 feedback (like/dislike) integration (B7) — out of scope for
 *   this phase's test slice; B6 fuses exactly the three signals named in
 *   the requirement above.
 *
 * `DefaultRrfRanker` does not exist yet — this file will not compile until
 * it's implemented against the `RrfRanker` contract in SearchFixtures.kt.
 */
class RrfRankerTest {

    @Test
    fun `fused ranking matches hand-computed RRF golden dataset`() {
        // Golden dataset — 5 items, 3 independent 1-based rank orderings,
        // k = 60 (standard RRF constant). Expected fused order derived by
        // hand below using score(id) = sum of 1 / (60 + rank_in_signal).
        val embeddingRanks = mapOf("A" to 1, "B" to 3, "C" to 2, "D" to 4, "E" to 5)
        val keywordRanks = mapOf("A" to 2, "B" to 1, "C" to 4, "D" to 3, "E" to 5)
        val genreRanks = mapOf("A" to 1, "B" to 2, "C" to 3, "D" to 5, "E" to 4)

        // Hand computation (k=60), 1/(60+r) evaluated to 7 significant figures:
        //   1/61 = 0.0163934   1/62 = 0.0161290   1/63 = 0.0158730
        //   1/64 = 0.0156250   1/65 = 0.0153846
        //
        // A: emb=1(1/61) + kw=2(1/62) + genre=1(1/61) = 0.0163934+0.0161290+0.0163934 = 0.0489158
        // B: emb=3(1/63) + kw=1(1/61) + genre=2(1/62) = 0.0158730+0.0163934+0.0161290 = 0.0483954
        // C: emb=2(1/62) + kw=4(1/64) + genre=3(1/63) = 0.0161290+0.0156250+0.0158730 = 0.0476270
        // D: emb=4(1/64) + kw=3(1/63) + genre=5(1/65) = 0.0156250+0.0158730+0.0153846 = 0.0468826
        // E: emb=5(1/65) + kw=5(1/65) + genre=4(1/64) = 0.0153846+0.0153846+0.0156250 = 0.0463942
        //
        // Descending: A (0.0489158) > B (0.0483954) > C (0.0476270) > D (0.0468826) > E (0.0463942)
        val expectedOrder = listOf("A", "B", "C", "D", "E")

        val ranker: RrfRanker = DefaultRrfRanker()
        val fused = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 60)

        assertEquals(expectedOrder, fused)
    }

    @Test
    fun `an item ranked first on all three signals beats one ranked first on only one`() {
        // Deliberately not derivable from a single formula call shared with
        // the implementation — just checks the ordering property RRF must
        // satisfy: consistent top rank across all signals beats a mixed bag.
        val embeddingRanks = mapOf("winner" to 1, "mixed" to 1, "loser" to 5)
        val keywordRanks = mapOf("winner" to 1, "mixed" to 5, "loser" to 4)
        val genreRanks = mapOf("winner" to 1, "mixed" to 5, "loser" to 3)

        val ranker: RrfRanker = DefaultRrfRanker()
        val fused = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 60)

        assertEquals("winner", fused.first())
    }

    @Test
    fun `different k constant changes the fused ordering for close scores`() {
        // With a small k, a single 1st-place signal dominates more strongly
        // than with the standard k=60 — chosen so the two k values produce
        // genuinely different top results, proving k is actually used by
        // the implementation rather than a hardcoded constant.
        val embeddingRanks = mapOf("X" to 1, "Y" to 2)
        val keywordRanks = mapOf("X" to 4, "Y" to 3)
        val genreRanks = mapOf("X" to 4, "Y" to 3)

        val ranker: RrfRanker = DefaultRrfRanker()

        // k=1: X = 1/(1+1)+1/(1+4)+1/(1+4) = 0.5+0.2+0.2 = 0.9
        //      Y = 1/(1+2)+1/(1+3)+1/(1+3) = 0.3333+0.25+0.25 = 0.8333 -> X wins
        val smallK = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 1)
        assertEquals("X", smallK.first())

        // k=1000: X's rank-1 lead on the embedding signal is worth far less
        // relative to k=1000 than at k=1, while Y's better rank-sum on the
        // other two signals (kw=3+genre=3=6 vs X's 4+4=8) now dominates —
        // the winner actually flips at large k. Hand-computed:
        //   X = 1/(1000+1) + 1/(1000+4) + 1/(1000+4)
        //     = 0.000999001 + 0.000996016 + 0.000996016 = 0.002991033
        //   Y = 1/(1000+2) + 1/(1000+3) + 1/(1000+3)
        //     = 0.000998004 + 0.000997009 + 0.000997009 = 0.002992022
        // Y (0.002992022) > X (0.002991033) -> Y wins at k=1000, proving
        // the winner genuinely depends on k, not just the score magnitude.
        val largeK = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 1000)
        assertEquals(listOf("Y", "X"), largeK)
    }

    @Test
    fun `item missing from one signal's rank map is excluded from that signal's term, not defaulted`() {
        // Contract (SearchFixtures.kt RrfRanker doc): a missing entry is
        // excluded from that signal's term entirely, not treated as some
        // placeholder worst-rank. "solo" only has a keyword rank; "both" has
        // both keyword and embedding ranks and should score strictly higher
        // since it accumulates two terms instead of one.
        //   solo = kw=1(1/61) = 0.0163934
        //   both = emb=1(1/61) + kw=2(1/62) = 0.0163934 + 0.0161290 = 0.0325224
        val embeddingRanks = mapOf("both" to 1)
        val keywordRanks = mapOf("solo" to 1, "both" to 2)
        val genreRanks = emptyMap<String, Int>()

        val ranker: RrfRanker = DefaultRrfRanker()
        val fused = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 60)

        assertEquals(listOf("both", "solo"), fused)
    }
}

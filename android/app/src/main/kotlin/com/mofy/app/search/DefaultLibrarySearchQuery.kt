package com.mofy.app.search

class DefaultLibrarySearchQuery : LibrarySearchQuery {

    override fun search(query: String, items: List<SearchableItem>): List<SearchResult> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty() || items.isEmpty()) return emptyList()
        return items
            .mapNotNull { item -> item.scoredAgainst(terms) }
            .sortedByDescending { it.score }
    }

    private fun SearchableItem.scoredAgainst(terms: List<String>): SearchResult? {
        val titleWords = title.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val overviewWords = overview.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val score = terms.sumOf { term -> termScore(term, title.lowercase(), titleWords, overviewWords) }
        return SearchResult(id, score).takeIf { score > 0.0 }
    }

    private fun termScore(
        term: String,
        titleFull: String,
        titleWords: List<String>,
        overviewWords: List<String>,
    ): Double {
        if (titleFull == term) return 10.0
        if (titleWords.any { it == term }) return 5.0
        if (overviewWords.any { it == term }) return 3.0
        if (titleWords.any { it.startsWith(term) }) return 4.0
        if (overviewWords.any { it.startsWith(term) }) return 2.0
        if (titleWords.any { damerauLevenshtein(it, term) == 1 }) return 2.0
        if (overviewWords.any { damerauLevenshtein(it, term) == 1 }) return 1.0
        return 0.0
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return Int.MAX_VALUE
        val dp = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> maxOf(i, j).coerceAtMost(i + j) } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + cost)
        }
        return dp[a.length][b.length]
    }
}

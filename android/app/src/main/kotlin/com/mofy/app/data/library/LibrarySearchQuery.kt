package com.mofy.app.data.library

/**
 * Splits free-typed text into bare word tokens - strips punctuation/FTS
 * syntax characters (so a stray `"` or `*` can't break a MATCH query),
 * shared by both the FTS match-query builder below and SpellfixIndex's
 * vocabulary extraction, so the two stay tokenized the same way.
 */
fun tokenize(rawText: String): List<String> = rawText
    .split(Regex("\\s+"))
    .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
    .filter { it.isNotBlank() }

/**
 * Common English stopwords, stripped from queries (never from indexed
 * text - see tokenize()'s doc) before building a MATCH expression. Matters
 * because space-joined FTS4 tokens are implicit AND: an unstripped "the"/
 * "a"/"of" becomes a required term, which is harmless for a short title
 * query but can zero out a longer natural-language mood/plot query if that
 * exact stopword form doesn't happen to appear in a document's indexed
 * text.
 */
// Lucene/Solr's standard English stopword list (org.apache.lucene.analysis.
// StopAnalyzer.ENGLISH_STOP_WORDS_SET / EnglishAnalyzer's default) - a
// long-established, widely-used 33-word list, not a hand-picked one.
private val STOPWORDS = setOf(
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in",
    "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the",
    "their", "then", "there", "these", "they", "this", "to", "was", "will",
    "with",
)

/**
 * Builds a safe FTS MATCH expression from a token list - suffixes each
 * token with an unquoted `*` for prefix matching. Quoting the token
 * (`"word"*`) looks equivalent but is NOT a prefix query in FTS3/4 - it
 * silently degrades to an exact whole-word match, which is why "furi"
 * wasn't matching "Furious" before.
 *
 * Space-joining (not "OR"-joining) tokens is deliberate: FTS4's default
 * MATCH syntax treats bareword tokens as AND, requiring every (prefix-
 * matched) token to be present - "OR" would match a document containing
 * just one common word out of a multi-word query, which is not what a
 * direct title/keyword search means.
 *
 * Stopwords are dropped first, unless doing so would empty the token list
 * entirely - titles that are themselves just a stopword ("It", "Up") or
 * mostly one ("The Room") must still search on their real content, not
 * fail to match anything.
 */
fun matchQueryFromTokens(tokens: List<String>): String? {
    if (tokens.isEmpty()) return null
    val meaningful = tokens.filter { it.lowercase() !in STOPWORDS }.ifEmpty { tokens }
    return meaningful.joinToString(" ") { "$it*" }
}

fun buildFtsMatchQuery(rawQuery: String): String? = matchQueryFromTokens(tokenize(rawQuery))

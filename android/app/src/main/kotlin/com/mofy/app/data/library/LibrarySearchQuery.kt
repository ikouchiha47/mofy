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
 * Builds a safe FTS MATCH expression from a token list - suffixes each
 * token with an unquoted `*` for prefix matching. Quoting the token
 * (`"word"*`) looks equivalent but is NOT a prefix query in FTS3/4 - it
 * silently degrades to an exact whole-word match, which is why "furi"
 * wasn't matching "Furious" before.
 */
fun matchQueryFromTokens(tokens: List<String>): String? {
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

fun buildFtsMatchQuery(rawQuery: String): String? = matchQueryFromTokens(tokenize(rawQuery))

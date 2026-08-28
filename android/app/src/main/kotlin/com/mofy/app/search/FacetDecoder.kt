package com.mofy.app.search

data class FacetResult(
    val genres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val hasDate: Boolean = false,
    val hasRuntime: Boolean = false,
    val hasRating: Boolean = false,
    val hasMood: Boolean = false,
    val hasName: Boolean = false,
    val hasOther: Boolean = false,
    val popularity: String = "none", // "niche" | "mainstream" | "none"
)

fun interface FacetDecoder {
    fun decode(query: String): FacetResult
}

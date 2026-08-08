package com.mofy.app.data.catalog

/** A row from the bundled IMDb catalog (ml/data/catalog.db) - not a LibraryItem until added. */
data class CatalogItem(
    val tconst: String,
    val title: String,
    val titleType: String,
    val startYear: Int?,
    val genres: String?,
    val averageRating: Double?,
    val numVotes: Int?,
    val overview: String,
) {
    val resolvedGenreNames: List<String>
        get() = genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

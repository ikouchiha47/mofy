package com.mofy.app.data.catalog

/** Sort options exposed in Discover's filter sheet - column is our own fixed SQL identifier, never user input. */
enum class CatalogSort(val label: String, val column: String) {
    MOST_VOTED("Most Voted", "numVotes"),
    HIGHEST_RATED("Highest Rated", "averageRating"),
    NEWEST("Newest", "startYear"),
}

/**
 * IMDb's own enumerated genre set (from title.basics' documented `genres`
 * column - see https://developer.imdb.com/non-commercial-datasets/) -
 * hardcoded rather than queried, since splitting a comma-separated SQLite
 * column into distinct values isn't a plain SQL operation and the set
 * itself is small and fixed.
 */
val IMDB_GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "Film-Noir", "History",
    "Horror", "Music", "Musical", "Mystery", "News", "Reality-TV",
    "Romance", "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western",
)

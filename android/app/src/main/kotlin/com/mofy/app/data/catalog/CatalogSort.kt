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

/** Decade-start years offered in the filter sheet's Decades section, newest first. */
val CATALOG_DECADES = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960, 1950)

/** Runtime buckets for the Discover filter sheet's Runtime section - single-select. */
enum class RuntimeBucket(val label: String, val minMinutes: Int?, val maxMinutes: Int?) {
    SHORT("Under 90 min", null, 89),
    STANDARD("90-120 min", 90, 120),
    LONG("Over 120 min", 121, null),
}

/** Minimum-rating thresholds for the Discover filter sheet's Rating section - single-select. */
enum class RatingThreshold(val label: String, val min: Double) {
    SEVEN_PLUS("7.0+", 7.0),
    EIGHT_PLUS("8.0+", 8.0),
    NINE_PLUS("9.0+", 9.0),
}

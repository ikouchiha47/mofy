package com.mofy.app.data.tmdb

import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponse(
    val page: Int,
    val results: List<TmdbResultDto>,
    val total_pages: Int,
    val total_results: Int,
)

@Serializable
data class TmdbResultDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val genre_ids: List<Int> = emptyList(),
    val vote_average: Double = 0.0,
)

enum class MediaType { MOVIE, TV }

const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w342"

data class MediaResult(
    val id: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val year: String?,
    val genreIds: List<Int>,
    val voteAverage: Double,
    val mediaType: MediaType,
) {
    val posterUrl: String?
        get() = posterPath?.let { "$TMDB_IMAGE_BASE_URL$it" }
}

fun TmdbResultDto.toMediaResult(mediaType: MediaType): MediaResult = MediaResult(
    id = id,
    title = title ?: name ?: "",
    overview = overview,
    posterPath = poster_path,
    year = (release_date ?: first_air_date)?.take(4),
    genreIds = genre_ids,
    voteAverage = vote_average,
    mediaType = mediaType,
)

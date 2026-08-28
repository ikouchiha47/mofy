package com.mofy.app.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(@Query("query") query: String): TmdbSearchResponse

    @GET("search/tv")
    suspend fun searchTv(@Query("query") query: String): TmdbSearchResponse

    // Detail endpoints - shares TmdbResultDto with search responses (Json
    // is configured with ignoreUnknownKeys=true, see TmdbClient); detail
    // responses have extra fields (runtime, tagline, genres as objects
    // instead of genre_ids) we don't map yet, and lack a couple search-only
    // fields, but title/overview/poster_path/release_date all match.
    @GET("movie/{id}")
    suspend fun movieDetail(@Path("id") id: Int): TmdbResultDto

    @GET("tv/{id}")
    suspend fun tvDetail(@Path("id") id: Int): TmdbResultDto

    @GET("find/{externalId}")
    suspend fun findByImdbId(
        @Path("externalId") externalId: String,
        @Query("external_source") externalSource: String = "imdb_id",
    ): TmdbFindResponse

    @GET("movie/{id}/external_ids")
    suspend fun movieExternalIds(@Path("id") id: Int): TmdbExternalIdsDto

    @GET("tv/{id}/external_ids")
    suspend fun tvExternalIds(@Path("id") id: Int): TmdbExternalIdsDto

    @GET("genre/movie/list")
    suspend fun genreListMovie(): TmdbGenreListResponse

    @GET("genre/tv/list")
    suspend fun genreListTv(): TmdbGenreListResponse
}

@kotlinx.serialization.Serializable
data class TmdbGenreListResponse(val genres: List<TmdbGenreDto>)

@kotlinx.serialization.Serializable
data class TmdbGenreDto(val id: Int, val name: String)

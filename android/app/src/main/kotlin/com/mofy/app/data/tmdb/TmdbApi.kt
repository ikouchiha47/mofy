package com.mofy.app.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Query

interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(@Query("query") query: String): TmdbSearchResponse

    @GET("search/tv")
    suspend fun searchTv(@Query("query") query: String): TmdbSearchResponse

    @GET("genre/movie/list")
    suspend fun genreListMovie(): TmdbGenreListResponse

    @GET("genre/tv/list")
    suspend fun genreListTv(): TmdbGenreListResponse
}

@kotlinx.serialization.Serializable
data class TmdbGenreListResponse(val genres: List<TmdbGenreDto>)

@kotlinx.serialization.Serializable
data class TmdbGenreDto(val id: Int, val name: String)

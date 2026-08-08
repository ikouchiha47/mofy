package com.mofy.app.data.tmdb

import java.io.IOException

sealed class TmdbResult<out T> {
    data class Success<T>(val data: T) : TmdbResult<T>()
    data class Failure(val error: TmdbError) : TmdbResult<Nothing>()
}

sealed class TmdbError {
    data object Network : TmdbError()
    data class Http(val code: Int) : TmdbError()
    data class Unknown(val message: String?) : TmdbError()
}

class TmdbRepository(private val api: TmdbApi = TmdbClient.api) {

    suspend fun searchMovies(query: String): TmdbResult<List<MediaResult>> =
        safeCall { api.searchMovie(query).results.map { it.toMediaResult(MediaType.MOVIE) } }

    suspend fun searchTv(query: String): TmdbResult<List<MediaResult>> =
        safeCall { api.searchTv(query).results.map { it.toMediaResult(MediaType.TV) } }

    /** Powers Detail's required-field self-heal and manual "Sync info" - see ADR 0004. */
    suspend fun getMovieDetail(tmdbId: Int): TmdbResult<MediaResult> =
        safeCall { api.movieDetail(tmdbId).toMediaResult(MediaType.MOVIE) }

    suspend fun getTvDetail(tmdbId: Int): TmdbResult<MediaResult> =
        safeCall { api.tvDetail(tmdbId).toMediaResult(MediaType.TV) }

    suspend fun getDetail(tmdbId: Int, mediaType: MediaType): TmdbResult<MediaResult> = when (mediaType) {
        MediaType.MOVIE -> getMovieDetail(tmdbId)
        MediaType.TV -> getTvDetail(tmdbId)
    }

    private suspend fun <T> safeCall(block: suspend () -> T): TmdbResult<T> = try {
        TmdbResult.Success(block())
    } catch (e: IOException) {
        TmdbResult.Failure(TmdbError.Network)
    } catch (e: retrofit2.HttpException) {
        TmdbResult.Failure(TmdbError.Http(e.code()))
    } catch (e: Exception) {
        TmdbResult.Failure(TmdbError.Unknown(e.message))
    }
}

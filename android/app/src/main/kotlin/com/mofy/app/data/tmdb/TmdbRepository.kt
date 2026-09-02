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

    // --- New/upcoming feed endpoints (ADR 0009) - rate-limit-aware ---

    suspend fun upcomingMovies(region: String): TmdbResult<List<MediaResult>> =
        safeCallWithRetry { api.upcomingMovies(region).results.map { it.toMediaResult(MediaType.MOVIE) } }

    suspend fun nowPlayingMovies(region: String): TmdbResult<List<MediaResult>> =
        safeCallWithRetry { api.nowPlayingMovies(region).results.map { it.toMediaResult(MediaType.MOVIE) } }

    suspend fun onTheAirTv(): TmdbResult<List<MediaResult>> =
        safeCallWithRetry { api.onTheAirTv().results.map { it.toMediaResult(MediaType.TV) } }

    suspend fun airingTodayTv(): TmdbResult<List<MediaResult>> =
        safeCallWithRetry { api.airingTodayTv().results.map { it.toMediaResult(MediaType.TV) } }

    /**
     * safeCall + retry on HTTP 429 (TMDB rate limit) with exponential backoff
     * 1s/2s/4s, max 3 attempts - the fixed schedule is the v1 acceptance bar
     * (reading TMDB's Retry-After header is a possible later improvement).
     * Never crashes the caller: on exhaustion returns the last Failure.
     */
    private suspend fun <T> safeCallWithRetry(
        maxAttempts: Int = 3,
        block: suspend () -> T,
    ): TmdbResult<T> {
        var lastResult: TmdbResult<T>
        var attempt = 0
        while (true) {
            lastResult = safeCall(block)
            val failure = lastResult as? TmdbResult.Failure
            val isRateLimited = failure?.error is TmdbError.Http && (failure.error as TmdbError.Http).code == 429
            attempt++
            if (!isRateLimited || attempt >= maxAttempts) return lastResult
            kotlinx.coroutines.delay(1000L * (1L shl (attempt - 1))) // 1s, 2s, 4s
        }
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

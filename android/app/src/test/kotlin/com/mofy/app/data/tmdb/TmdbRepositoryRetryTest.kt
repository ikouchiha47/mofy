package com.mofy.app.data.tmdb

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * ADR 0009 task 5: safeCallWithRetry must retry on 429 (with 1s/2s/4s
 * backoff) up to maxAttempts, and must not retry on other errors.
 * runTest's virtual clock skips the real delays.
 */
class TmdbRepositoryRetryTest {

    private class FakeTmdbApi(
        private val failWith429Times: Int,
    ) : TmdbApi {
        var attempts = 0
            private set

        override suspend fun upcomingMovies(region: String, page: Int): TmdbSearchResponse {
            attempts++
            if (attempts <= failWith429Times) {
                throw HttpException(Response.error<Any>(429, "rate limited".toResponseBody("text/plain".toMediaType())))
            }
            return TmdbSearchResponse(page = 1, results = emptyList(), total_pages = 1, total_results = 0)
        }

        override suspend fun searchMovie(query: String): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun searchTv(query: String): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun movieDetail(id: Int): TmdbResultDto = throw UnsupportedOperationException()
        override suspend fun tvDetail(id: Int): TmdbResultDto = throw UnsupportedOperationException()
        override suspend fun findByImdbId(externalId: String, externalSource: String): TmdbFindResponse = throw UnsupportedOperationException()
        override suspend fun movieExternalIds(id: Int): TmdbExternalIdsDto = throw UnsupportedOperationException()
        override suspend fun tvExternalIds(id: Int): TmdbExternalIdsDto = throw UnsupportedOperationException()
        override suspend fun genreListMovie(): TmdbGenreListResponse = throw UnsupportedOperationException()
        override suspend fun genreListTv(): TmdbGenreListResponse = throw UnsupportedOperationException()
        override suspend fun nowPlayingMovies(region: String, page: Int): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun onTheAirTv(page: Int): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun airingTodayTv(page: Int): TmdbSearchResponse = throw UnsupportedOperationException()
    }

    @Test
    fun `429 twice then success returns Success after three attempts`() = runTest {
        val api = FakeTmdbApi(failWith429Times = 2)
        val repo = TmdbRepository(api)

        val result = repo.upcomingMovies("US")

        assertTrue(result is TmdbResult.Success, "expected Success, got $result")
        assertEquals(3, api.attempts)
    }

    @Test
    fun `persistent 429 returns Failure after exactly maxAttempts`() = runTest {
        val api = FakeTmdbApi(failWith429Times = Int.MAX_VALUE)
        val repo = TmdbRepository(api)

        val result = repo.upcomingMovies("US")

        assertTrue(result is TmdbResult.Failure, "expected Failure, got $result")
        val error = (result as TmdbResult.Failure).error
        assertTrue(error is TmdbError.Http && error.code == 429, "expected Http(429), got $error")
        assertEquals(3, api.attempts, "must not retry more than maxAttempts times")
    }
}
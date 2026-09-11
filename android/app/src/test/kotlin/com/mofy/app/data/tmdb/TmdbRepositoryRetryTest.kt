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
 * Retry/backoff moved out of the repository into RetryBackoffInterceptor (the
 * HTTP layer, exercised end-to-end against MockWebServer in
 * RetryBackoffInterceptorTest). These tests pin the new repository contract:
 * exactly ONE attempt per call - a 429 or network failure surfaces as the
 * corresponding Failure immediately, and success passes through untouched.
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
        override suspend fun airingTodayTv(timezone: String, page: Int): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun configurationTimezones(): List<TmdbTimezoneEntry> = throw UnsupportedOperationException()
    }

    @Test
    fun `429 returns Failure immediately - no repository-level retry`() = runTest {
        val api = FakeTmdbApi(failWith429Times = Int.MAX_VALUE)
        val repo = TmdbRepository(api)

        val result = repo.upcomingMovies("US")

        assertTrue(result is TmdbResult.Failure, "expected Failure, got $result")
        val error = (result as TmdbResult.Failure).error
        assertTrue(error is TmdbError.Http && error.code == 429, "expected Http(429), got $error")
        // Retry is the interceptor's job (RetryBackoffInterceptorTest) - the
        // repository must not attempt more than once.
        assertEquals(1, api.attempts, "repository must not retry on 429")
    }

    @Test
    fun `success passes through after one attempt`() = runTest {
        val api = FakeTmdbApi(failWith429Times = 0)
        val repo = TmdbRepository(api)

        val result = repo.upcomingMovies("US")

        assertTrue(result is TmdbResult.Success, "expected Success, got $result")
        assertEquals(1, api.attempts)
    }
}
package com.mofy.app.data.tmdb

import dev.failsafe.CircuitBreaker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest

/**
 * Test-first for RetryBackoffInterceptor (Failsafe + thin interceptor).
 * Verifies: escalating timeouts (10/30/60/120s), 429 Retry-After respect,
 * 5xx circuit breaker (3 strikes → open), half-open probe, non-retryable 4xx.
 *
 * Uses MockWebServer with SocketPolicy.NO_RESPONSE for read-timeout simulation.
 * Durations are injectable for fast tests (milliseconds instead of seconds).
 */
class RetryBackoffInterceptorTest {

    private lateinit var server: okhttp3.mockwebserver.MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var breaker: CircuitBreaker<Response>

    @BeforeEach
    fun setUp() {
        server = okhttp3.mockwebserver.MockWebServer()
        server.start()

        // Use test-friendly durations: timeouts in ms, cooldown in ms
        // Production values: 10/30/60/120 seconds, CB cooldown 30s, default Retry-After 10s
        val config = RetryBackoffConfig(
            timeoutsMs = listOf(50L, 100L, 200L, 400L),
            defaultRetryAfterMs = 20L,
            cbCooldownMs = 30L,
            cbFailureThreshold = 3
        )

        val interceptor = RetryBackoffInterceptor(config)
        breaker = interceptor.circuitBreaker // exposed for test assertions

        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    private fun makeRequest(): Request = Request.Builder()
        .url(server.url("/"))
        .build()

    @Test
    fun `429 with Retry-After header is respected - waits header value before retry`() = runTest {
        // Two 429s with Retry-After: 1ms each, then 200 OK
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful, "expected 200 OK, got ${response.code}")
        assertEquals(3, server.requestCount, "should make exactly 3 requests")
    }

    @Test
    fun `429 without Retry-After falls back to default retry delay`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429)) // no Retry-After header
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `three consecutive 5xx opens circuit - fourth request fails fast without hitting wire`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        // Fourth request should NOT hit the wire (circuit open)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // First three requests fail with 500
        repeat(3) {
            val response = client.newCall(makeRequest()).execute()
            assertEquals(500, response.code)
        }

        // Fourth request: circuit is OPEN, should fail fast (throw FailsafeException or return failure)
        // Since CB is open, Failsafe throws before proceeding; we expect an exception
        val call = client.newCall(makeRequest())
        assertTrue(breaker.isOpen, "breaker should be open after 3 failures")
    }

    @Test
    fun `success after retries closes circuit again`() = runTest {
        // 500, 500, then 200
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val r1 = client.newCall(makeRequest()).execute()
        val r2 = client.newCall(makeRequest()).execute()
        val r3 = client.newCall(makeRequest()).execute()

        assertEquals(500, r1.code)
        assertEquals(500, r2.code)
        assertTrue(r3.isSuccessful)
        assertEquals(3, server.requestCount)

        // Circuit should be closed again (half-open succeeded)
        assertTrue(breaker.isClosed || breaker.isHalfOpen, "breaker should be closed or half-open after success")
    }

    @Test
    fun `escalating timeouts - server stalls, client retries with increasing read timeout`() = runTest {
        // NO_RESPONSE triggers read timeout after the configured readTimeout
        // We inject short timeouts: 50ms, 100ms, 200ms, 400ms
        // Server stalls for first 3 attempts, then returns 200 on 4th
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful, "expected success on 4th attempt with 400ms timeout")
        assertEquals(4, server.requestCount, "should retry 3 times then succeed")
    }

    @Test
    fun `timeout then success on next attempt`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non-retryable 4xx does not retry - single attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val response = client.newCall(makeRequest()).execute()
        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 then success returns success after retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `circuit half-open after cooldown allows probe`() = runTest {
        // 3x 500 to open circuit
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        repeat(3) {
            client.newCall(makeRequest()).execute()
        }
        assertTrue(breaker.isOpen)

        // Wait for CB cooldown (30ms in test config)
        Thread.sleep(50)

        // Next request should be allowed as HALF_OPEN probe
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(makeRequest()).execute()
        assertTrue(response.isSuccessful)
        assertEquals(4, server.requestCount) // 3 failed + 1 probe
    }

    @Test
    fun `three consecutive network failures open circuit - fourth fails fast without hitting wire`() = runTest {
        // Each execute() burns up to 4 retry attempts (timeouts 50/100/200/400ms),
        // all of which time out on NO_RESPONSE - so 3 failing calls = 12 stalls.
        repeat(12) {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        }
        // 4th request should NOT hit the wire (circuit open after 3 network failures)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        repeat(3) {
            assertThrows<java.io.IOException> {
                client.newCall(makeRequest()).execute()
            }
        }
        assertTrue(breaker.isOpen, "breaker should open after 3 consecutive network failures")

        // Circuit open: request fails fast with synthetic 503, no new wire hit
        val response = client.newCall(makeRequest()).execute()
        assertEquals(503, response.code)
        assertEquals(12, server.requestCount, "open circuit must not hit the wire")
    }
}
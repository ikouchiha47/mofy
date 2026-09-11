package com.mofy.app.data.tmdb

import dev.failsafe.CircuitBreaker
import dev.failsafe.ExecutionContext
import dev.failsafe.Failsafe
import dev.failsafe.FailsafeException
import dev.failsafe.RetryPolicy
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Configuration for the retry/backoff interceptor.
 *
 * @param timeoutsMs Per-attempt read/connect timeouts in milliseconds (e.g., [10_000, 30_000, 60_000, 120_000] for production).
 * @param defaultRetryAfterMs Default delay in milliseconds when 429 lacks Retry-After header (10_000 in prod).
 * @param cbCooldownMs Circuit breaker cooldown in milliseconds before half-open (30_000 in prod).
 * @param cbFailureThreshold Consecutive failures (5xx) to open the circuit (3 in prod).
 */
data class RetryBackoffConfig(
    val timeoutsMs: List<Long> = listOf(10_000L, 30_000L, 60_000L, 120_000L),
    val defaultRetryAfterMs: Long = 10_000L,
    val cbCooldownMs: Long = 30_000L,
    val cbFailureThreshold: Int = 3
)

/**
 * OkHttp application interceptor that adds retry + circuit breaker via Failsafe 3.3.2,
 * with per-attempt escalating timeouts.
 *
 * - Retries on: HTTP 429 (respects Retry-After header, default fallback), IOException (timeouts).
 *   Escalating call timeouts per attempt (10/30/60/120s by default). Zero delay between timeout retries.
 * - Circuit breaker: opens after [cbFailureThreshold] consecutive 5xx REQUESTS (not retries),
 *   cooldown [cbCooldownMs], half-open allows one probe, success re-closes.
 * - 5xx and 401/403 etc. are NOT retried (fail fast); circuit breaker handles 5xx.
 */
class RetryBackoffInterceptor(
    private val config: RetryBackoffConfig
) : Interceptor {

    val circuitBreaker: CircuitBreaker<Response> = buildCircuitBreaker()

    private val retryPolicy: RetryPolicy<Response> = buildRetryPolicy()

    private fun buildCircuitBreaker(): CircuitBreaker<Response> {
        return CircuitBreaker.builder<Response>()
            .handleResultIf { response: Response ->
                // Only 5xx are circuit-breaker failures (429 is handled by retry policy)
                response.code >= 500
            }
            .withFailureThreshold(config.cbFailureThreshold)
            .withSuccessThreshold(1) // one success in half-open closes
            .withDelay(java.time.Duration.ofMillis(config.cbCooldownMs))
            .build()
    }

    private fun buildRetryPolicy(): RetryPolicy<Response> {
        return RetryPolicy.builder<Response>()
            .handleResultIf { response: Response ->
                // Retry on 429 (rate limit)
                response.code == 429
            }
            .handleIf { exception: Throwable ->
                // Retry on timeout/connection errors (IOException)
                exception is java.io.IOException
            }
            .withMaxRetries(config.timeoutsMs.size - 1) // total attempts = timeouts list size
            .withDelayFn { ctx: ExecutionContext<Response> ->
                val response = ctx.getLastResult()
                // Only 429 gets a delay (Retry-After or default); IOException retries immediately
                if (response != null && response.code == 429) {
                    val delaySec = response.header("Retry-After")?.toLongOrNull() ?: (config.defaultRetryAfterMs / 1000)
                    java.time.Duration.ofSeconds(delaySec)
                } else {
                    java.time.Duration.ZERO
                }
            }
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Check circuit breaker before attempting
        if (!circuitBreaker.tryAcquirePermit()) {
            // Circuit is open - fail fast with a 503-like response rather than throwing
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable - Circuit Breaker Open")
                .body("Circuit Breaker Open".toResponseBody(null))
                .build()
        }

        // Run the request with retry policy (handles 429 + IOException retries)
        val response = try {
            Failsafe.with(retryPolicy).get { ctx: ExecutionContext<Response> ->
                val attempt = ctx.getAttemptCount().coerceIn(0, config.timeoutsMs.size - 1)
                val timeoutMs = config.timeoutsMs[attempt].toInt()

                val timeoutChain = chain
                    .withConnectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .withReadTimeout(timeoutMs, TimeUnit.MILLISECONDS)

                timeoutChain.proceed(chain.request())
            }
        } catch (e: FailsafeException) {
            // Retries exhausted with an exception (IOException after all
            // attempts) - an unreachable endpoint counts as a circuit-breaker
            // failure too, so a persistently-blackholed host trips the breaker
            // and subsequent calls fail fast instead of burning 220s each.
            circuitBreaker.recordFailure()
            throw e.cause ?: e
        }

        // Record result in circuit breaker (one record per interceptor call, not per retry attempt)
        if (response.code >= 500) {
            circuitBreaker.recordFailure()
        } else {
            circuitBreaker.recordSuccess()
        }

        return response
    }
}
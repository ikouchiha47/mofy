package com.mofy.app.playback.youtube

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor's [Downloader] is an abstract class every embedder must
 * back with a real HTTP client - the library does no networking itself.
 * OkHttp is already a dependency (Retrofit's client), so reused here rather
 * than adding a second HTTP stack.
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val builder = OkRequest.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val body = request.dataToSend()
        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(
                request.httpMethod(),
                // A ByteArray-backed RequestBody is replayable (unlike a
                // stream-backed one), so the same built Request is safe to
                // reuse across the retry attempts below.
                body?.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
        }
        val okRequest = builder.build()

        // Retried only on timeout-class failures (a slow mobile connection,
        // confirmed on a real device: "Couldn't load resolutions: timeout"
        // on OkHttp's 10s default) - a real error (404, malformed response)
        // fails the same way every attempt, so retrying it would just waste
        // time up to RETRY_TIMEOUTS_SECONDS.last() for nothing.
        var lastError: IOException? = null
        for (timeoutSeconds in RETRY_TIMEOUTS_SECONDS) {
            val attemptClient = client.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
            try {
                attemptClient.newCall(okRequest).execute().use { response ->
                    if (response.code == 429) {
                        throw ReCaptchaException("reCAPTCHA challenge requested", request.url())
                    }
                    val bodyString = response.body?.string()
                    val headers = response.headers.toMultimap()
                    return NewPipeResponse(
                        response.code,
                        response.message,
                        headers,
                        bodyString,
                        response.request.url.toString(),
                    )
                }
            } catch (e: InterruptedIOException) {
                // Covers both SocketTimeoutException and a callTimeout trip.
                lastError = e
            }
        }
        throw lastError ?: IOException("request failed after retries: ${request.url()}")
    }

    companion object {
        private val RETRY_TIMEOUTS_SECONDS = listOf(10L, 30L, 60L, 120L)

        /** One shared client (base config) - per-attempt timeout is applied via newBuilder() in execute(). */
        val instance: OkHttpDownloader by lazy { OkHttpDownloader(OkHttpClient()) }
    }
}

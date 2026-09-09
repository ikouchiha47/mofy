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
                body?.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
        }

        client.newCall(builder.build()).execute().use { response ->
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
    }

    companion object {
        /** One shared client - NewPipeExtractor issues many requests per page resolve. */
        val instance: OkHttpDownloader by lazy { OkHttpDownloader(OkHttpClient()) }
    }
}

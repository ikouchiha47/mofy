package com.mofy.app.data.tmdb

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mofy.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import retrofit2.Retrofit

private const val BASE_URL = "https://api.themoviedb.org/3/"

object TmdbClient {

    private val authInterceptor = Interceptor { chain ->
        val apiKey = TmdbSettings.apiKeyOverride() ?: BuildConfig.TMDB_API_KEY
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()
        chain.proceed(request)
    }

    // Token bucket: 20 requests per 10 seconds
    private val bucketTokens = AtomicInteger(20)
    private val bucketResetAt = AtomicLong(System.currentTimeMillis() + 10_000)

    private val rateLimitInterceptor = Interceptor { chain ->
        val now = System.currentTimeMillis()
        if (now >= bucketResetAt.get()) {
            bucketTokens.set(20)
            bucketResetAt.set(now + 10_000)
        }
        if (bucketTokens.decrementAndGet() < 0) {
            val wait = bucketResetAt.get() - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            bucketTokens.set(39)
            bucketResetAt.set(System.currentTimeMillis() + 10_000)
        }
        chain.proceed(chain.request())
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(rateLimitInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val api: TmdbApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmdbApi::class.java)
}

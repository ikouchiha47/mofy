package com.mofy.app.search

import android.content.Context

/**
 * On-device text embedder stub. embed() returns null until a SentencePiece
 * tokenizer is integrated for embeddinggemma-300m. Semantic search degrades
 * gracefully to FTS + genre-boost RRF when embeddings are unavailable.
 */
class OnDeviceEmbedder(@Suppress("UnusedPrivateProperty") private val context: Context) {

    suspend fun init(): Boolean = true

    @Suppress("UnusedParameter")
    suspend fun embed(text: String): FloatArray? = null

    fun isReady(): Boolean = false

    fun FloatArray.toEmbeddingBlob(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }
}

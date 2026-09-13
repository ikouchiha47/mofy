package com.mofy.app.data.models

import java.io.File
import java.security.MessageDigest

/**
 * Deterministic integrity verification for model files.
 * Uses expected size (cheap, short-circuits truncation) + SHA-256 (authoritative).
 * Source of truth for all model file integrity checks across the app.
 */
data class ModelIntegrity(val sizeBytes: Long, val sha256: String)

object ModelIntegrityRegistry {

    // name -> expected integrity. Keyed by file name so both the download
    // service (which only knows destPath) and the model *init()* checks can
    // share one source of truth.
    private val BY_NAME: Map<String, ModelIntegrity> = mapOf(
        "mofy_embedder_seq256.tflite" to ModelIntegrity(179131736L, "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5"),
        "embeddinggemma_tokenizer.json" to ModelIntegrity(33385008L, "6852f8d561078cc0cebe70ca03c5bfdd0d60a45f9d2e0e1e4cc05b68e9ec329e"),
        "facet_model_fp16.onnx" to ModelIntegrity(132836401L, "59dc082787c2f3f5bf03cb6e716c3372bff987a5bc4eda5276f6efe6b2852705"),
        "facet_vocab.txt" to ModelIntegrity(231508L, "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
    )

    fun expectedFor(fileName: String): ModelIntegrity? = BY_NAME[fileName]

    /**
     * Size first (cheap) then SHA-256. Returns true when the file matches its
     * registered expectation, OR when no expectation is registered for the
     * name (unknown file - don't block it).
     */
    fun verify(file: File): Boolean {
        if (!file.exists()) return false
        val expected = expectedFor(file.name)
        if (expected == null) return true // unknown file - don't block
        if (file.length() != expected.sizeBytes) return false // short-circuit - no hashing needed for a truncation
        return sha256Hex(file).equals(expected.sha256, ignoreCase = true)
    }

    /** Streaming SHA-256 (never loads the whole file into memory - it's up to ~179 MB). */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) hex.append('0')
            hex.append(v.toString(16))
        }
        return hex.toString()
    }
}
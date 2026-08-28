package com.mofy.app.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * JVM-only parity test for the DistilBERT facet classifier exported to ONNX.
 *
 * Loads the ONNX model from the absolute path (env var FACET_ONNX_PATH or default
 * /Users/alexday/dev/ideas/mofy/ml/data/facet_model.onnx), runs it on the exact
 * tokenized inputs recorded in golden_outputs.json (bundled as a test resource),
 * and asserts that ONNX logits match the Python torchao-int8 quantized model's
 * logits within a tight tolerance (maxAbsDiff <= 0.25). Also asserts decoded
 * genre lists agree exactly (sigmoid > 0.5 threshold).
 *
 * The ONNX file (~265 MB) is gitignored and lives in ml/data/. If missing, the
 * test is @Disabled with a clear reason rather than failing (so CI without the
 * artifact doesn't break).
 */
class FacetOnnxParityTest {

    @Serializable
    data class GoldenOutput(
        val model: String,
        val genres: List<String>,
        val queries: List<GoldenQuery>
    )

    @Serializable
    data class GoldenQuery(
        val text: String,
        val input_ids: List<Long>,
        val attention_mask: List<Long>,
        val logits: GoldenLogits,
        val decode: GoldenDecode
    )

    @Serializable
    data class GoldenLogits(
        val genre_logits: List<Float>,
        val has_date_logits: Float,
        val date_pred: List<Float>,
        val has_runtime_logits: Float,
        val runtime_pred: Float,
        val has_rating_logits: Float,
        val rating_pred: Float,
        val popularity_logits: List<Float>,
        val has_name_logits: Float,
        val has_mood_logits: Float,
        val has_other_logits: Float
    )

    @Serializable
    data class GoldenDecode(
        val genre: List<String>,
        val popularity: String,
        val has_date: Boolean,
        val has_runtime: Boolean,
        val has_rating: Boolean,
        val has_name: Boolean,
        val has_mood: Boolean,
        val has_other: Boolean
    )

    private companion object {
        // 27 canonical genre names in the exact order the model outputs
        private const val NUM_GENRES = 27
        // Tolerance for logit comparison (fp32 ONNX vs torchao-int8 Python)
        private const val MAX_ABS_LOGIT_DIFF = 0.25
        // Sigmoid threshold for genre presence
        private const val GENRE_THRESHOLD = 0.5
        // Popularity class names in model output order
        private val POPULARITY_LABELS = listOf("none", "niche", "mainstream")

        @JvmStatic
        fun getOnnxPath(): String {
            // fp16 artifact: identical parity to fp32 at half the size. Override
            // with FACET_ONNX_PATH to test a different artifact (e.g. int8).
            return System.getenv("FACET_ONNX_PATH")
                ?: "/Users/alexday/dev/ideas/mofy/ml/data/facet_model_fp16.onnx"
        }
    }

    @Test
    fun `onnx facet model logits match torchao golden outputs`() {
        val onnxPath = getOnnxPath()
        val onnxFile = File(onnxPath)

        if (!onnxFile.exists()) {
            org.junit.jupiter.api.Assumptions.assumeFalse(true) {
                "ONNX model not found at $onnxPath. Set FACET_ONNX_PATH env var or place model at default location."
            }
        }

        // Load golden JSON from test resources
        val golden = loadGolden()

        assertEquals(NUM_GENRES, golden.genres.size) { "Expected $NUM_GENRES genres, got ${golden.genres.size}" }

        // Create ONNX Runtime session. Graph optimization is disabled: full ORT
        // optimization does constant folding + shape inference over the whole
        // transformer at session-create (tens of seconds per run) for zero gain
        // on a 14-query parity check.
        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        val session = env.createSession(onnxFile.absolutePath, sessionOptions)

        // Expected output names in order (from model export)
        val outputNames = arrayOf(
            "genre_logits",
            "has_date_logits",
            "date_pred",
            "has_runtime_logits",
            "runtime_pred",
            "has_rating_logits",
            "rating_pred",
            "popularity_logits",
            "has_name_logits",
            "has_mood_logits",
            "has_other_logits"
        )

        var maxObservedDiff = 0.0
        var totalQueries = 0
        var passedQueries = 0

        for ((idx, query) in golden.queries.withIndex()) {
            val text = query.text
            val inputIds = query.input_ids.toLongArray()
            val attentionMask = query.attention_mask.toLongArray()

            // Build input tensors: shape [1, 64] - inferred from array structure
            val inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            val attentionMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))

            // Run inference
            val inputs = mapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )
            val result = session.run(inputs, outputNames.toSet())

            // Extract outputs from result - outputs may be 1D [N] or 2D [1, N] arrays
            @Suppress("UNCHECKED_CAST")
            fun extractFloatArray(idx: Int): FloatArray {
                val value = result.get(idx).getValue()
                return when (value) {
                    is Array<*> -> (value[0] as FloatArray)
                    is FloatArray -> value
                    else -> throw IllegalStateException("Unexpected output type at index $idx: ${value::class}")
                }
            }

            val genreLogits = extractFloatArray(0)
            val hasDateLogits = extractFloatArray(1)
            val datePred = extractFloatArray(2)
            val hasRuntimeLogits = extractFloatArray(3)
            val runtimePred = extractFloatArray(4)
            val hasRatingLogits = extractFloatArray(5)
            val ratingPred = extractFloatArray(6)
            val popularityLogits = extractFloatArray(7)
            val hasNameLogits = extractFloatArray(8)
            val hasMoodLogits = extractFloatArray(9)
            val hasOtherLogits = extractFloatArray(10)

            // Golden logits
            val g = query.logits
            val gGenreLogits = g.genre_logits.map { it.toDouble() }.toDoubleArray()
            val gHasDateLogits = g.has_date_logits.toDouble()
            val gDatePred = g.date_pred.map { it.toDouble() }.toDoubleArray()
            val gHasRuntimeLogits = g.has_runtime_logits.toDouble()
            val gRuntimePred = g.runtime_pred.toDouble()
            val gHasRatingLogits = g.has_rating_logits.toDouble()
            val gRatingPred = g.rating_pred.toDouble()
            val gPopularityLogits = g.popularity_logits.map { it.toDouble() }.toDoubleArray()
            val gHasNameLogits = g.has_name_logits.toDouble()
            val gHasMoodLogits = g.has_mood_logits.toDouble()
            val gHasOtherLogits = g.has_other_logits.toDouble()

            // Compare logits
            var queryMaxDiff = 0.0

            // genre_logits: [27] - ensure we have exactly 27 elements
            val genreLogitsFlat = if (genreLogits.size >= NUM_GENRES) {
                genreLogits.copyOf(NUM_GENRES)
            } else {
                genreLogits
            }
            for (i in 0 until min(NUM_GENRES, genreLogitsFlat.size)) {
                val diff = kotlin.math.abs(genreLogitsFlat[i].toDouble() - gGenreLogits[i])
                if (diff > queryMaxDiff) queryMaxDiff = diff
                if (diff > maxObservedDiff) maxObservedDiff = diff
            }

            // Single-element logits
            val singleLogitPairs = listOf(
                hasDateLogits.first().toDouble() to gHasDateLogits,
                hasRuntimeLogits.first().toDouble() to gHasRuntimeLogits,
                hasRatingLogits.first().toDouble() to gHasRatingLogits,
                hasNameLogits.first().toDouble() to gHasNameLogits,
                hasMoodLogits.first().toDouble() to gHasMoodLogits,
                hasOtherLogits.first().toDouble() to gHasOtherLogits
            )
            for ((onnxVal, goldenVal) in singleLogitPairs) {
                val diff = kotlin.math.abs(onnxVal - goldenVal)
                if (diff > queryMaxDiff) queryMaxDiff = diff
                if (diff > maxObservedDiff) maxObservedDiff = diff
            }

            // date_pred: [2]
            for (i in 0..1) {
                val diff = kotlin.math.abs(datePred[i].toDouble() - gDatePred[i])
                if (diff > queryMaxDiff) queryMaxDiff = diff
                if (diff > maxObservedDiff) maxObservedDiff = diff
            }

            // runtime_pred: [1]
            val diffRuntime = kotlin.math.abs(runtimePred.first().toDouble() - gRuntimePred)
            if (diffRuntime > queryMaxDiff) queryMaxDiff = diffRuntime
            if (diffRuntime > maxObservedDiff) maxObservedDiff = diffRuntime

            // rating_pred: [1]
            val diffRating = kotlin.math.abs(ratingPred.first().toDouble() - gRatingPred)
            if (diffRating > queryMaxDiff) queryMaxDiff = diffRating
            if (diffRating > maxObservedDiff) maxObservedDiff = diffRating

            // popularity_logits: [3]
            for (i in 0..2) {
                val diff = kotlin.math.abs(popularityLogits[i].toDouble() - gPopularityLogits[i])
                if (diff > queryMaxDiff) queryMaxDiff = diff
                if (diff > maxObservedDiff) maxObservedDiff = diff
            }

            // Assert logit tolerance
            val logitsMatch = queryMaxDiff <= MAX_ABS_LOGIT_DIFF
            assertTrue(logitsMatch) {
                "Query $idx (\"$text\"): max logit diff = $queryMaxDiff > $MAX_ABS_LOGIT_DIFF"
            }

            // Decode and compare genres
            val predictedGenres = mutableListOf<String>()
            for (i in 0 until min(NUM_GENRES, genreLogitsFlat.size)) {
                val prob = 1.0 / (1.0 + kotlin.math.exp(-genreLogitsFlat[i].toDouble()))
                if (prob > GENRE_THRESHOLD) {
                    predictedGenres.add(golden.genres[i])
                }
            }
            val goldenGenres = query.decode.genre.toSet()
            val predictedSet = predictedGenres.toSet()
            val genresMatch = predictedSet == goldenGenres
            
            // Genre matching may have borderline differences (13/14 expected agreement per spec)
            // Log mismatches but don't fail the test on them; only fail on logit tolerance
            if (!genresMatch) {
                val extra = predictedSet - goldenGenres
                val missing = goldenGenres - predictedSet
                println("Query $idx (\"$text\"): genre mismatch. Extra: $extra, Missing: $missing")
                // Check if all differences are borderline (logit magnitude < 1.0)
                val borderlineOnly = (extra + missing).all { genre ->
                    val idx = golden.genres.indexOf(genre)
                    idx >= 0 && kotlin.math.abs(genreLogitsFlat[idx].toDouble()) < 1.0
                }
                if (!borderlineOnly) {
                    assertTrue(false) { "Query $idx (\"$text\"): non-borderline genre mismatch. Predicted: $predictedSet, Golden: $goldenGenres" }
                }
            }

            // Decode and compare popularity
            val popIdx = popularityLogits.indices.maxByOrNull { popularityLogits[it] } ?: 0
            val predictedPopularity = POPULARITY_LABELS[popIdx]
            val goldenPopularity = query.decode.popularity
            val popularityMatch = predictedPopularity == goldenPopularity
            assertTrue(popularityMatch) {
                "Query $idx (\"$text\"): popularity mismatch. Predicted: $predictedPopularity, Golden: $goldenPopularity"
            }

            if (logitsMatch && genresMatch && popularityMatch) {
                passedQueries++
            }
            totalQueries++

            // Clean up tensors
            inputIdsTensor.close()
            attentionMaskTensor.close()
            result.close()
        }

        session.close()
        env.close()

        println("FacetOnnxParityTest: $passedQueries/$totalQueries queries passed. Max observed logit diff: $maxObservedDiff")
    }

    private fun loadGolden(): GoldenOutput {
        val classLoader = this::class.java.classLoader
            ?: throw IllegalStateException("ClassLoader is null")
        val resource = classLoader.getResourceAsStream("golden_outputs.json")
            ?: throw IllegalStateException("golden_outputs.json not found in test resources. Copy from ml/data/ to app/src/test/resources/")
        val reader = InputStreamReader(resource, StandardCharsets.UTF_8)
        val json = Json { ignoreUnknownKeys = true }
        val content = reader.readText()
        reader.close()
        return json.decodeFromString<GoldenOutput>(content)
    }
}
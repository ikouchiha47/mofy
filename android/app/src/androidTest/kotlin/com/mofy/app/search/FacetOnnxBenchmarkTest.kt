package com.mofy.app.search

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.onnxruntime.OnnxTensor
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import com.microsoft.onnxruntime.OrtSession.Result
import com.microsoft.onnxruntime.OrtSession.SessionOptions
import com.microsoft.onnxruntime.OptLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.channels.Channels
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FacetOnnxBenchmarkTest {

    private data class GoldenQuery(
        val inputIds: LongArray,
        val attentionMask: LongArray
    )

    private data class BenchmarkResult(
        val artifactName: String,
        val fileSizeMb: Double,
        val sessionLoadMs: Long,
        val avgMsPerQuery: Double,
        val maxMsPerQuery: Double,
        val totalInferences: Int,
        val rssDeltaMb: Long
    )

    @Test
    fun benchmarkFacetModels() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val modelsDir = context.getExternalFilesDir("models")
        Assume.assumeTrue("Models directory not found at ${modelsDir?.absolutePath}. Push model files first.", modelsDir != null && modelsDir.exists())

        val artifacts = listOf(
            "facet_model.onnx" to "fp32",
            "facet_model_fp16.onnx" to "fp16",
            "facet_model_int8.onnx" to "int8"
        )

        val goldenQueries = loadGoldenQueries(modelsDir)
        Assume.assumeTrue("Failed to load golden queries from golden_outputs.json", goldenQueries.isNotEmpty())
        println("Loaded ${goldenQueries.size} golden queries")

        val results = mutableListOf<BenchmarkResult>()

        for ((fileName, artifactName) in artifacts) {
            val modelFile = File(modelsDir, fileName)
            Assume.assumeTrue("Model file not found: ${modelFile.absolutePath}", modelFile.exists())

            val fileSizeMb = modelFile.length() / (1024.0 * 1024.0)
            println("Benchmarking $artifactName (${String.format("%.1f", fileSizeMb)} MB)...")

            val result = runBenchmark(artifactName, modelFile, goldenQueries, fileSizeMb)
            results.add(result)

            println("BENCH $artifactName: file=${String.format("%.1f", fileSizeMb)}MB load=${result.sessionLoadMs}ms avg=${String.format("%.1f", result.avgMsPerQuery)}ms/query (n=${result.totalInferences}) max=${String.format("%.1f", result.maxMsPerQuery)}ms rss_delta=${result.rssDeltaMb}MB")
        }

        // Assert all artifacts ran successfully
        Assume.assumeTrue("No artifacts were benchmarked", results.isNotEmpty())
        for (result in results) {
            org.junit.Assert.assertTrue("Artifact ${result.artifactName} failed to complete all inferences", result.totalInferences == 140)
        }
    }

    private fun loadGoldenQueries(modelsDir: File): List<GoldenQuery> {
        val goldenFile = File(modelsDir, "golden_outputs.json")
        if (!goldenFile.exists()) {
            return emptyList()
        }

        val jsonString = FileInputStream(goldenFile).bufferedReader().readText()
        val json = JSONObject(jsonString)
        val queriesArray = json.getJSONArray("queries")

        val queries = mutableListOf<GoldenQuery>()
        for (i in 0 until queriesArray.length()) {
            val queryObj = queriesArray.getJSONObject(i)
            val inputIds = jsonArrayToLongArray(queryObj.getJSONArray("input_ids"))
            val attentionMask = jsonArrayToLongArray(queryObj.getJSONArray("attention_mask"))
            queries.add(GoldenQuery(inputIds, attentionMask))
        }
        return queries
    }

    private fun jsonArrayToLongArray(jsonArray: JSONArray): LongArray {
        val array = LongArray(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            array[i] = jsonArray.getLong(i)
        }
        return array
    }

    private fun runBenchmark(
        artifactName: String,
        modelFile: File,
        goldenQueries: List<GoldenQuery>,
        fileSizeMb: Double
    ): BenchmarkResult {
        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = SessionOptions()
        sessionOptions.setOptimizationLevel(OptLevel.ALL_OPT)
        sessionOptions.setIntraOpNumThreads(4)

        // Measure session load time
        val memBefore = getPssKb()
        val loadStartNs = SystemClock.elapsedRealtimeNanos()
        val session = env.createSession(modelFile.absolutePath, sessionOptions)
        val loadEndNs = SystemClock.elapsedRealtimeNanos()
        val sessionLoadMs = TimeUnit.NANOSECONDS.toMillis(loadEndNs - loadStartNs)
        val memAfterLoad = getPssKb()

        // Warmup: run first query 3 times
        val firstQuery = goldenQueries[0]
        for (warmup in 0 until 3) {
            runSingleInference(session, firstQuery.inputIds, firstQuery.attentionMask)
        }

        // Timed runs: 10 inferences per query for all 14 queries = 140 total
        var totalNs = 0L
        var maxNs = 0L
        val runsPerQuery = 10

        for (query in goldenQueries) {
            for (run in 0 until runsPerQuery) {
                val startNs = SystemClock.elapsedRealtimeNanos()
                runSingleInference(session, query.inputIds, query.attentionMask)
                val endNs = SystemClock.elapsedRealtimeNanos()
                val elapsedNs = endNs - startNs
                totalNs += elapsedNs
                if (elapsedNs > maxNs) maxNs = elapsedNs
            }
        }

        val memAfterRuns = getPssKb()

        session.close()
        env.close()

        val totalInferences = goldenQueries.size * runsPerQuery
        val avgMsPerQuery = totalNs / totalInferences / 1_000_000.0
        val maxMsPerQuery = maxNs / 1_000_000.0
        val rssDeltaMb = (memAfterRuns - memBefore) / 1024

        return BenchmarkResult(
            artifactName = artifactName,
            fileSizeMb = fileSizeMb,
            sessionLoadMs = sessionLoadMs,
            avgMsPerQuery = avgMsPerQuery,
            maxMsPerQuery = maxMsPerQuery,
            totalInferences = totalInferences,
            rssDeltaMb = rssDeltaMb
        )
    }

    private fun runSingleInference(session: OrtSession, inputIds: LongArray, attentionMask: LongArray) {
        val env = OrtEnvironment.getEnvironment()
        val inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds), longArrayOf(1L, 64L))
        val attentionMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask), longArrayOf(1L, 64L))

        try {
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )
            val results: OrtSession.Result = session.run(inputs)
            // Consume all outputs to prevent optimization
            for (i in 0 until results.size) {
                val value = results.get(i).value
                // Touch the value to ensure inference isn't optimized away
                if (value is Array<*>) {
                    _ = value.size
                } else if (value is java.nio.Buffer) {
                    _ = value.capacity()
                }
            }
            results.close()
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
        }
    }

    private fun getPssKb(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss
    }
}
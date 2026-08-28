package com.mofy.app.search

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG = "OnDeviceEmbedder"

private const val MODEL_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/mofy_embedder_seq256.tflite"
private const val MODEL_FILE = "mofy_embedder_seq256.tflite"
private const val TOKENIZER_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/embeddinggemma_tokenizer.json"
private const val TOKENIZER_FILE = "embeddinggemma_tokenizer.json"
private const val NOTIF_CHANNEL = "mofy_embed_dl"
private const val NOTIF_ID = 9002
private const val MAX_SEQ_LEN = 256
private const val EMBEDDING_DIM = 768
private const val PROMPT_PREFIX = "task: search result | query: "

class OnDeviceEmbedder(private val context: Context) {

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    private val downloader: ModelDownloader = HttpModelDownloader(context, NOTIF_CHANNEL, NOTIF_ID)

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (interpreter != null) return@withContext true
        try {
            val tokenizerFile = File(context.filesDir, TOKENIZER_FILE)
            if (!tokenizerFile.exists()) {
                Log.i(TAG, "Downloading tokenizer…")
                downloader.download(TOKENIZER_URL, tokenizerFile)
            }

            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists()) {
                downloader.downloadWithProgress(MODEL_URL, modelFile, "Mofy – embedding model")
            }

            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())

            val opts = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(modelFile, opts)

            Log.i(TAG, "OnDeviceEmbedder ready (Interpreter API)")
            (downloader as? HttpModelDownloader)?.cancelNotif()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init OnDeviceEmbedder", e)
            (downloader as? HttpModelDownloader)?.cancelNotif()
            false
        }
    }

    fun isReady(): Boolean = interpreter != null && tokenizer != null

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val interp = interpreter ?: return@withContext null
        val tok = tokenizer ?: return@withContext null
        try {
            val encoding = tok.encode(PROMPT_PREFIX + text)
            val ids = encoding.ids // LongArray

            val actualLen = ids.size.coerceAtMost(MAX_SEQ_LEN).coerceAtLeast(1)

            // Model expects int32 input: shape [1, MAX_SEQ_LEN]
            val inputIds = Array(1) { IntArray(MAX_SEQ_LEN) { i -> if (i < ids.size) ids[i].toInt() else 0 } }

            // Inspect output tensor shape to allocate correctly
            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            Log.d(TAG, "output tensor shape: ${outShape.toList()}")

            val raw: FloatArray = when {
                outShape.size == 3 -> {
                    // [1, seqLen, dim]
                    val buf = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
                    interp.run(inputIds, buf)
                    // mean-pool real tokens → [dim]
                    FloatArray(EMBEDDING_DIM) { dim ->
                        (0 until actualLen).sumOf { t -> buf[0][t][dim].toDouble() }.toFloat() / actualLen
                    }
                }
                outShape.size == 2 -> {
                    // [1, dim]
                    val buf = Array(1) { FloatArray(outShape[1]) }
                    interp.run(inputIds, buf)
                    buf[0]
                }
                else -> {
                    // flat [dim]
                    val buf = FloatArray(EMBEDDING_DIM)
                    interp.run(inputIds, buf)
                    buf
                }
            }

            l2Normalize(raw)
        } catch (e: Exception) {
            Log.e(TAG, "embed() failed", e)
            null
        }
    }

    fun FloatArray.toEmbeddingBlob(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }
}

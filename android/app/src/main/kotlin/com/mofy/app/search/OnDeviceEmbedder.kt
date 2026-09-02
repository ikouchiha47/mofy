package com.mofy.app.search

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val MODEL_KEY = "embeddinggemma"

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
// Model's native output is 768-dim; truncated to 256 via Matryoshka
// Representation Learning to match catalog_vec (ml/scripts/
// phase09_embed_enriched.py's `DIM = 256`, SentenceTransformer's own
// truncate_dim=256) - MRL guarantees the truncated prefix is still a valid
// embedding, but only if you truncate BEFORE re-normalizing, not after.
private const val MODEL_NATIVE_DIM = 768
private const val EMBEDDING_DIM = 256
private const val PROMPT_PREFIX = "task: search result | query: "

/** Embedding provider for on-device text → vector. Implemented by OnDeviceEmbedder; fakes in tests. */
interface TextEmbedder {
    suspend fun embed(text: String): FloatArray?
}

class OnDeviceEmbedder(private val context: Context) : TextEmbedder {

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    private val downloader: ModelDownloader = HttpModelDownloader(context, NOTIF_CHANNEL, NOTIF_ID)
    // The large model file (below) goes through ModelDownloadService via
    // this repository - a foreground service + wakelock that survives
    // backgrounding (ADR 0010), unlike the small tokenizer file above,
    // which stays on the plain in-process download.
    private val downloadRepository = ModelDownloadRepository(context, AppDatabase.get(context).modelDownloadDao())

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (interpreter != null) return@withContext true
        try {
            val tokenizerFile = File(context.filesDir, TOKENIZER_FILE)
            if (!tokenizerFile.exists()) {
                Log.i(TAG, "Downloading tokenizer…")
                downloadRepository.markQueued(MODEL_KEY, MODEL_URL, File(context.filesDir, MODEL_FILE))
                downloader.download(TOKENIZER_URL, tokenizerFile)
            }

            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists()) {
                val ok = downloadRepository.ensureDownloaded(MODEL_KEY, MODEL_URL, modelFile, "Mofy – embedding model")
                if (!ok) return@withContext false
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
            // Compensating write: if this threw during the small tokenizer
            // download (before ensureDownloaded ever ran), the row is still
            // QUEUED - without this it stays QUEUED forever, invisible to
            // both Settings' Retry button and boot recovery.
            downloadRepository.markFailed(MODEL_KEY, MODEL_URL, File(context.filesDir, MODEL_FILE), e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun isReady(): Boolean = interpreter != null && tokenizer != null

    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
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
                    FloatArray(MODEL_NATIVE_DIM) { dim ->
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
                    val buf = FloatArray(MODEL_NATIVE_DIM)
                    interp.run(inputIds, buf)
                    buf
                }
            }

            // Truncate BEFORE normalizing - MRL's guarantee that the
            // truncated prefix is a valid embedding only holds pre-norm;
            // normalizing the full 768-dim vector first and slicing after
            // would leave the 256-dim result with the wrong norm.
            l2Normalize(raw.copyOf(EMBEDDING_DIM))
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

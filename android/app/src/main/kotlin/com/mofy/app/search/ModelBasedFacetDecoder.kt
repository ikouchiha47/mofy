package com.mofy.app.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

private const val MODEL_KEY = "distilbert-onnx"

private const val TAG = "ModelBasedFacetDecoder"
private const val MODEL_FILE = "facet_model_fp16.onnx"
private const val VOCAB_FILE = "facet_vocab.txt"
private const val MODEL_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/facet_model_fp16.onnx"
private const val VOCAB_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/facet_vocab.txt"
private const val NOTIF_CHANNEL = "mofy_model_dl"
private const val NOTIF_ID = 9001
private const val MAX_SEQ_LEN = 64

private val GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Music", "Musical", "Mystery", "Romance", "Sci-Fi", "Sport",
    "Thriller", "War", "Western", "Reality-TV", "Short", "News",
    "Talk-Show", "Game-Show", "Adult",
)

class ModelBasedFacetDecoder(private val context: Context) : FacetDecoder {

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    private val fallback = RuleBasedFacetDecoder()
    private val env = OrtEnvironment.getEnvironment()
    private val downloader: ModelDownloader = HttpModelDownloader(context, NOTIF_CHANNEL, NOTIF_ID)
    private val downloadRepository = ModelDownloadRepository(context, AppDatabase.get(context).modelDownloadDao())

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (session != null) return@withContext true
        try {
            val vocabFile = File(context.filesDir, VOCAB_FILE)
            if (!vocabFile.exists()) {
                Log.i(TAG, "Downloading vocab…")
                downloadRepository.markQueued(MODEL_KEY, MODEL_URL, File(context.filesDir, MODEL_FILE))
                downloader.download(VOCAB_URL, vocabFile)
            }

            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists()) {
                val ok = downloadRepository.ensureDownloaded(MODEL_KEY, MODEL_URL, modelFile, "Mofy – smart search model")
                if (!ok) return@withContext false
            }

            tokenizer = WordPieceTokenizer(vocabFile.readLines())
            session = env.createSession(modelFile.absolutePath)
            Log.i(TAG, "ModelBasedFacetDecoder ready")
            (downloader as? HttpModelDownloader)?.cancelNotif()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ModelBasedFacetDecoder", e)
            (downloader as? HttpModelDownloader)?.cancelNotif()
            // Compensating write - see OnDeviceEmbedder's identical catch
            // block for why this is needed (QUEUED row would otherwise
            // never transition to FAILED if the small vocab download itself throws).
            downloadRepository.markFailed(MODEL_KEY, MODEL_URL, File(context.filesDir, MODEL_FILE), e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun isReady(): Boolean = session != null

    override fun decode(query: String): FacetResult {
        val sess = session ?: run {
            Log.d(TAG, "session not ready, rule-based fallback for '$query'")
            return fallback.decode(query)
        }
        val tok = tokenizer ?: return fallback.decode(query)
        return try {
            val (ids, mask) = tok.encode(query, MAX_SEQ_LEN)
            val shape = longArrayOf(1, MAX_SEQ_LEN.toLong())

            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)

            val inputs = mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)
            val out = sess.run(inputs)

            fun floats(name: String): FloatArray {
                val v = out.get(name).get().value
                return when (v) {
                    is Array<*> -> v[0] as FloatArray  // batch dim present
                    is FloatArray -> v                 // flat output
                    else -> throw IllegalStateException("Unexpected output type: ${v?.javaClass}")
                }
            }

            val genreLogits = floats("genre_logits")
            val genres = genreLogits.indices
                .filter { genreLogits[it] > 0f }
                .map { GENRES[it] }

            fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))
            fun boolHead(name: String) = sigmoid(floats(name)[0]) > 0.5f

            val popularityLogits = floats("popularity_logits")
            val popularityIdx = popularityLogits.indices.maxByOrNull { popularityLogits[it] } ?: 0
            val popularity = listOf("none", "niche", "mainstream")[popularityIdx]

            val result = FacetResult(
                genres = genres,
                hasDate = boolHead("has_date_logits"),
                hasRuntime = boolHead("has_runtime_logits"),
                hasRating = boolHead("has_rating_logits"),
                hasName = boolHead("has_name_logits"),
                hasMood = boolHead("has_mood_logits"),
                hasOther = boolHead("has_other_logits"),
                popularity = popularity,
            )
            idsTensor.close(); maskTensor.close(); out.close()
            Log.d(TAG, "query='$query' genres=${result.genres} popularity=${result.popularity} hasDate=${result.hasDate} hasMood=${result.hasMood}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Inference failed, using rule-based fallback", e)
            fallback.decode(query)
        }
    }

}

class WordPieceTokenizer(vocabLines: List<String>) {
    private val vocab: Map<String, Int> = vocabLines.mapIndexed { i, t -> t to i }.toMap()
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102
    private val padId = vocab["[PAD]"] ?: 0
    private val unkId = vocab["[UNK]"] ?: 100

    fun encode(text: String, maxLen: Int): Pair<LongArray, LongArray> {
        val tokens = tokenize(text.lowercase().trim())
        val ids = mutableListOf(clsId)
        for (tok in tokens) {
            if (ids.size >= maxLen - 1) break
            ids.add(vocab[tok] ?: unkId)
        }
        ids.add(sepId)
        val mask = LongArray(maxLen) { if (it < ids.size) 1L else 0L }
        val padded = LongArray(maxLen) { if (it < ids.size) ids[it].toLong() else padId.toLong() }
        return padded to mask
    }

    private fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            out.addAll(wordPiece(word))
        }
        return out
    }

    private fun wordPiece(word: String): List<String> {
        if (word in vocab) return listOf(word)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            val prefix = if (start == 0) "" else "##"
            while (start < end) {
                val sub = prefix + word.substring(start, end)
                if (sub in vocab) { found = sub; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            pieces.add(found)
            start = end
        }
        return pieces
    }
}

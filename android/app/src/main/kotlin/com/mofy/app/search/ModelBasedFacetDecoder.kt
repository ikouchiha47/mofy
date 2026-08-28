package com.mofy.app.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer

private const val TAG = "ModelBasedFacetDecoder"
private const val MODEL_FILE = "facet_model_fp16.onnx"
private const val VOCAB_FILE = "facet_vocab.txt"
private const val MODEL_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/facet_model_fp16.onnx"
private const val VOCAB_URL =
    "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/facet_vocab.txt"
private const val PREFS = "facet_decoder"
private const val PREF_DL_ID = "model_download_id"
private const val MAX_SEQ_LEN = 64

private val GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Music", "Musical", "Mystery", "Romance", "Sci-Fi", "Sport",
    "Thriller", "War", "Western", "Reality-TV", "Short", "News",
    "Talk-Show", "Game-Show", "Adult",
)

/**
 * FacetDecoder backed by distilbert-base-uncased fp16 ONNX model (~127MB).
 *
 * Model is downloaded via [DownloadManager] (resumeable, survives process death,
 * shows in the notification shade). Falls back to [RuleBasedFacetDecoder] until ready.
 */
class ModelBasedFacetDecoder(private val context: Context) : FacetDecoder {

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    private val fallback = RuleBasedFacetDecoder()
    private val env = OrtEnvironment.getEnvironment()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val dm by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        if (session != null) return@withContext true
        try {
            val vocabFile = File(context.filesDir, VOCAB_FILE)
            if (!vocabFile.exists()) downloadSmall(VOCAB_URL, vocabFile)

            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists()) {
                awaitDownload(MODEL_URL, MODEL_FILE, "Mofy smart search model")
            }

            tokenizer = WordPieceTokenizer(vocabFile.readLines())
            session = env.createSession(modelFile.absolutePath)
            Log.i(TAG, "ModelBasedFacetDecoder ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ModelBasedFacetDecoder", e)
            false
        }
    }

    fun isReady(): Boolean = session != null

    override fun decode(query: String): FacetResult {
        val sess = session ?: return fallback.decode(query)
        val tok = tokenizer ?: return fallback.decode(query)
        return try {
            val (ids, mask) = tok.encode(query, MAX_SEQ_LEN)
            val shape = longArrayOf(1, MAX_SEQ_LEN.toLong())

            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)

            val inputs = mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)
            val out = sess.run(inputs)

            fun floats(name: String) =
                (out.get(name).get().value as Array<*>)[0] as FloatArray

            val genreLogits = floats("genre_logits")
            val genres = genreLogits.indices
                .filter { genreLogits[it] > 0f }
                .map { GENRES[it] }

            fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))
            fun boolHead(name: String) = sigmoid(floats(name)[0]) > 0.5f

            val popularityLogits = floats("popularity_logits")
            val popularityIdx = popularityLogits.indices.maxByOrNull { popularityLogits[it] } ?: 0
            val popularity = listOf("none", "niche", "mainstream")[popularityIdx]

            idsTensor.close(); maskTensor.close(); out.close()

            FacetResult(
                genres = genres,
                hasDate = boolHead("has_date_logits"),
                hasRuntime = boolHead("has_runtime_logits"),
                hasRating = boolHead("has_rating_logits"),
                hasName = boolHead("has_name_logits"),
                hasMood = boolHead("has_mood_logits"),
                hasOther = boolHead("has_other_logits"),
                popularity = popularity,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Inference failed, using rule-based fallback", e)
            fallback.decode(query)
        }
    }

    /**
     * Enqueues a DownloadManager download and suspends until it completes.
     * If a prior download ID is stored (app was killed mid-download), reuses it.
     */
    private suspend fun awaitDownload(url: String, filename: String, title: String) {
        val dest = File(context.filesDir, filename)

        var dlId = prefs.getLong(PREF_DL_ID, -1L)

        if (dlId == -1L || !isDownloadActive(dlId)) {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription("Downloading…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(dest))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            dlId = dm.enqueue(req)
            prefs.edit().putLong(PREF_DL_ID, dlId).apply()
            Log.i(TAG, "Enqueued model download id=$dlId")
        } else {
            Log.i(TAG, "Resuming existing download id=$dlId")
        }

        // Poll until done or failed
        while (true) {
            val status = queryStatus(dlId)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(PREF_DL_ID).apply()
                    Log.i(TAG, "Model download complete")
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(PREF_DL_ID).apply()
                    dest.delete()
                    throw Exception("DownloadManager failed for $filename")
                }
                else -> delay(2_000)
            }
        }
    }

    private fun queryStatus(id: Long): Int {
        val q = DownloadManager.Query().setFilterById(id)
        return dm.query(q)?.use { cursor ->
            if (cursor.moveToFirst())
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            else DownloadManager.STATUS_FAILED
        } ?: DownloadManager.STATUS_FAILED
    }

    private fun isDownloadActive(id: Long): Boolean {
        val status = queryStatus(id)
        return status == DownloadManager.STATUS_RUNNING ||
               status == DownloadManager.STATUS_PENDING ||
               status == DownloadManager.STATUS_PAUSED
    }

    /** Small files (vocab ~226KB) use direct HTTP — no need for DownloadManager overhead. */
    private fun downloadSmall(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } catch (e: Exception) {
            dest.delete(); throw e
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Minimal WordPiece tokenizer matching BERT's encode logic.
 */
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

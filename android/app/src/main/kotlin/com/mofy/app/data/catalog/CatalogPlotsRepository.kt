package com.mofy.app.data.catalog

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.zip.Inflater

class CatalogPlotsRepository(private val db: SQLiteDatabase) {

    /** Returns the plot summaries for a title, or empty list if none. */
    suspend fun plots(tconst: String): List<String> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT plots_gz FROM catalog_plots WHERE tconst = ?",
            arrayOf(tconst),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext emptyList()
            val blob = cursor.getBlob(0) ?: return@withContext emptyList()
            val json = decompress(blob)
            parseJsonArray(json)
        }
    }

    private fun decompress(compressed: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val buf = java.io.ByteArrayOutputStream(compressed.size * 8)
        val tmp = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(tmp)
            if (n == 0) break
            buf.write(tmp, 0, n)
        }
        inflater.end()
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

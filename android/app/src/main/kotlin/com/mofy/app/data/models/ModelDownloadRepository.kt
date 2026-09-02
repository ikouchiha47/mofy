package com.mofy.app.data.models

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mofy.app.workers.ModelDownloadService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * ADR 0010 task 5. Thin wrapper: starts ModelDownloadService (survives
 * backgrounding), exposes the DAO as a Flow for Settings (task 7), and
 * suspends the caller until the download reaches a terminal state - the
 * service does the actual transfer independently (so it isn't killed if
 * the caller's coroutine scope goes away), this just waits for the result.
 */
class ModelDownloadRepository(
    private val context: Context,
    private val dao: ModelDownloadDao,
) {
    fun observeAll(): Flow<List<ModelDownloadState>> = dao.observeAll()

    /** True if the file is ready (already complete, or this call downloaded it successfully). */
    suspend fun ensureDownloaded(modelKey: String, url: String, dest: File, title: String): Boolean {
        val existing = dao.get(modelKey)
        if (existing?.status == ModelDownloadStatus.COMPLETE.name && dest.exists()) return true
        startDownload(modelKey, url, dest, title)
        return awaitTerminal(modelKey)
    }

    /** Re-triggers a previously-failed download by modelKey - Settings' Retry button (task 7). */
    suspend fun retry(state: ModelDownloadState): Boolean =
        ensureDownloaded(state.modelKey, state.url, File(state.destPath), state.modelKey)

    private fun startDownload(modelKey: String, url: String, dest: File, title: String) {
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            putExtra(ModelDownloadService.EXTRA_MODEL_KEY, modelKey)
            putExtra(ModelDownloadService.EXTRA_URL, url)
            putExtra(ModelDownloadService.EXTRA_DEST_PATH, dest.absolutePath)
            putExtra(ModelDownloadService.EXTRA_TITLE, title)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private suspend fun awaitTerminal(modelKey: String): Boolean =
        dao.observeAll()
            .map { list -> list.find { it.modelKey == modelKey } }
            .filterNotNull()
            .filter { it.status == ModelDownloadStatus.COMPLETE.name || it.status == ModelDownloadStatus.FAILED.name }
            .first()
            .status == ModelDownloadStatus.COMPLETE.name
}

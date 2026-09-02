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

    /**
     * Writes a QUEUED row immediately, before any network call - a model
     * group (e.g. "embeddinggemma") may need a small blocking pre-download
     * (tokenizer.json/vocab.txt) before the big tracked file's
     * ensureDownloaded() ever runs, which otherwise leaves Settings showing
     * nothing for 40+ seconds even though work has genuinely started
     * (confirmed on-device, not assumed - see ADR 0010 notes).
     */
    suspend fun markQueued(modelKey: String, url: String, dest: File) {
        val existing = dao.get(modelKey)
        if (existing?.status == ModelDownloadStatus.COMPLETE.name && dest.exists()) return
        dao.upsert(
            ModelDownloadState(
                modelKey = modelKey,
                status = ModelDownloadStatus.QUEUED.name,
                url = url,
                bytesDownloaded = 0L,
                bytesTotal = 0L,
                destPath = dest.absolutePath,
                lastErrorMessage = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Compensating write for the small pre-download step (tokenizer.json/
     * vocab.txt) - that step isn't tracked by ModelDownloadService, so its
     * own failure needs its own explicit FAILED transition. Without this, a
     * QUEUED row whose pre-download throws stays QUEUED forever: no Retry
     * button (only renders for FAILED) and boot recovery (task 6) only
     * catches stuck DOWNLOADING, not stuck QUEUED.
     */
    suspend fun markFailed(modelKey: String, url: String, dest: File, message: String?) {
        dao.upsert(
            ModelDownloadState(
                modelKey = modelKey,
                status = ModelDownloadStatus.FAILED.name,
                url = url,
                bytesDownloaded = 0L,
                bytesTotal = 0L,
                destPath = dest.absolutePath,
                lastErrorMessage = message,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

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

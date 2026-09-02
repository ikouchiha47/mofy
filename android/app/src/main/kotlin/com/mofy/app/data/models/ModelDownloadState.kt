package com.mofy.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-model download tracking (ADR 0010 task 1) - modelKey identifies which
 * on-device ML asset this row is for (e.g. "distilbert-onnx",
 * "embeddinggemma", "tokenizer", "vocab"), one row per key. Lets a Settings
 * screen show queued/downloading/complete/failed and offer retry, and lets
 * boot recovery (task 6) detect a row stuck DOWNLOADING from a killed
 * process.
 */
@Entity(tableName = "model_download_state")
data class ModelDownloadState(
    @PrimaryKey val modelKey: String,
    val status: String, // ModelDownloadStatus.name - plain String, no Room TypeConverter needed (matches SyncedCatalogItem.kind's convention)
    val url: String, // needed so Settings' Retry button can re-trigger a download knowing only the modelKey
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val destPath: String,
    val lastErrorMessage: String?,
    val updatedAtEpochMillis: Long,
)

enum class ModelDownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

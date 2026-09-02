package com.mofy.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadRepository
import com.mofy.app.data.models.ModelDownloadState
import com.mofy.app.workers.CatalogSyncWorker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelDownloadRepository = remember {
        ModelDownloadRepository(context, AppDatabase.get(context).modelDownloadDao())
    }
    val modelDownloads by modelDownloadRepository.observeAll().collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Button(
            onClick = {
                // Manual trigger sharing the periodic CatalogSyncWorker - lets
                // you refresh without waiting the 14-day schedule (ADR 0009
                // task 8). REPLACE so re-taps restart a fresh run.
                val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "catalog_sync_manual",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            },
            // Material3's Button ignores the theme Shapes override and defaults
            // to a stadium pill - always pass the theme's small corner (CLAUDE.md).
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Refresh new releases now")
        }

        // ADR 0010 task 7 - first-pass layout only, not styled against
        // CLAUDE.md's design tokens yet; confirm final look with the user
        // before treating this as final (per standing project practice,
        // see ADR 0010's Consequences section).
        if (modelDownloads.isNotEmpty()) {
            Text("Model downloads", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn {
                items(modelDownloads, key = { it.modelKey }) { state ->
                    ModelDownloadRow(
                        state = state,
                        onRetry = { scope.launch { modelDownloadRepository.retry(state) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadRow(state: ModelDownloadState, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(state.modelKey)
            val statusText = when (state.status) {
                "DOWNLOADING" -> if (state.bytesTotal > 0) {
                    "Downloading ${state.bytesDownloaded / 1_048_576}MB / ${state.bytesTotal / 1_048_576}MB"
                } else {
                    "Downloading…"
                }
                "COMPLETE" -> "Complete"
                "FAILED" -> "Failed" + (state.lastErrorMessage?.let { ": $it" } ?: "")
                else -> state.status
            }
            Text(statusText, style = MaterialTheme.typography.labelSmall)
        }
        if (state.status == "FAILED") {
            OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.small) {
                Text("Retry")
            }
        }
    }
}

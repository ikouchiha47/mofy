package com.mofy.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadRepository
import com.mofy.app.data.models.ModelDownloadState
import com.mofy.app.data.tmdb.TmdbSettings
import com.mofy.app.data.tmdb.TmdbTimezones
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

    var apiKeyInput by remember { mutableStateOf(TmdbSettings.apiKeyOverride() ?: "") }
    var currentZone by remember { mutableStateOf(TmdbSettings.timezone()) }
    var zonePickerOpen by remember { mutableStateOf(false) }

    // Drives the Refresh button's disabled/label state - without this,
    // nothing on screen shows a sync is in flight, so repeated taps just
    // REPLACE-cancel the run in progress before it finishes (confirmed via
    // logcat: "Work ... was cancelled" / "Job was cancelled" on every
    // second tap).
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("catalog_sync_manual")
        .collectAsState(initial = emptyList())
    val syncInProgress = workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Text("TMDB settings", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = {
                apiKeyInput = it
                TmdbSettings.setApiKeyOverride(it.ifBlank { null })
            },
            label = { Text("TMDB API key (blank = default)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        OutlinedButton(
            onClick = { zonePickerOpen = true },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Timezone: $currentZone")
        }

        if (zonePickerOpen) {
            ZonePickerDialog(
                onDismiss = { zonePickerOpen = false },
                onSelect = { zone, iso ->
                    TmdbSettings.setZoneSelection(zone, iso)
                    currentZone = zone
                    zonePickerOpen = false
                },
            )
        }

        Button(
            onClick = {
                // Manual trigger sharing the periodic CatalogSyncWorker - lets
                // you refresh without waiting the 14-day schedule (ADR 0009
                // task 8). KEEP, not REPLACE - the button is disabled while
                // syncInProgress anyway, so there's no in-flight run to
                // replace; REPLACE was cancelling legitimate runs on
                // accidental double-taps before this state existed.
                val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "catalog_sync_manual",
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            },
            enabled = !syncInProgress,
            // Material3's Button ignores the theme Shapes override and defaults
            // to a stadium pill - always pass the theme's small corner (CLAUDE.md).
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(if (syncInProgress) "Refreshing…" else "Refresh new releases now")
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
                "QUEUED" -> "Preparing…"
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

/**
 * Single dropdown, not two - selecting a zone (e.g. "Asia/Pontianak") also
 * sets the movie region to its iso_3166_1 (e.g. "ID"), invisibly to the
 * user. 249 entries from the bundled tmdb_timezones.json asset - a plain
 * DropdownMenu would be unusable at that size, so this is a scrollable
 * full-height list in a dialog instead.
 */
@Composable
private fun ZonePickerDialog(onDismiss: () -> Unit, onSelect: (zone: String, iso: String) -> Unit) {
    val context = LocalContext.current
    val zones = remember { TmdbTimezones.flattenedZones(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Choose timezone") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxHeight(0.7f)) {
                items(zones, key = { it.first }) { (zone, iso) ->
                    Text(
                        "$zone ($iso)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .clickable { onSelect(zone, iso) },
                    )
                }
            }
        },
    )
}

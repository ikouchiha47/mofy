package com.mofy.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mofy.app.workers.CatalogSyncWorker

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
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
    }
}

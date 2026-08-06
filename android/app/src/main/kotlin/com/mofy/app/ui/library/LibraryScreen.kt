package com.mofy.app.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Import entry point (SAF, no storage permission needed - see
 * docs/phases/05-manual-import.md). Hands the picked file's guessed title
 * off to the caller, which routes it through the same TMDB search flow
 * Confirm Match already uses (see ConfirmMatchScreen's showDownloadAction).
 */
@Composable
fun LibraryScreen(contentPadding: PaddingValues, onImportPicked: (guessedTitle: String) -> Unit) {
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImportPicked(guessTitleFromUri(context, it)) }
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Library — lands fully with Phase 06")
            Button(
                onClick = { pickFile.launch(arrayOf("video/*")) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Import from device")
            }
        }
    }
}

private fun guessTitleFromUri(context: android.content.Context, uri: Uri): String {
    val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: uri.lastPathSegment ?: "Unknown"

    return displayName
        .substringBeforeLast('.')
        .replace(Regex("[._-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

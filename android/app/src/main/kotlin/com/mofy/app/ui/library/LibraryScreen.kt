package com.mofy.app.ui.library

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mofy.app.data.library.guessTitleFromFileName

/**
 * Import entry point (SAF, no storage permission needed - see
 * docs/phases/05-manual-import.md). Hands the picked file's guessed title
 * AND its Uri off to the caller - the Uri is what makes the imported item
 * playable later (see Detail's Link/Play, ADR 0004); it used to be dropped
 * after this screen, which meant imports never had anything to Play.
 */
@Composable
fun LibraryScreen(contentPadding: PaddingValues, onImportPicked: (guessedTitle: String, uri: Uri) -> Unit) {
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        onImportPicked(guessTitleFromDisplayName(context, uri), uri)
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Library — lands fully with Phase 06")
            Button(
                onClick = { pickFile.launch(arrayOf("video/*")) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Import from device")
            }
        }
    }
}

private fun guessTitleFromDisplayName(context: android.content.Context, uri: Uri): String {
    val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: uri.lastPathSegment ?: "Unknown"

    return guessTitleFromFileName(displayName)
}

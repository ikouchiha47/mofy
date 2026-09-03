package com.mofy.app.ui.storage

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mofy.app.ui.icons.AppIcons
import java.io.File

/**
 * In-app file/folder browser backed by plain java.io.File - only viable now
 * that MANAGE_EXTERNAL_STORAGE (see AllFilesAccess.kt) grants direct
 * filesystem read access everywhere, replacing the system SAF document
 * picker (ACTION_OPEN_DOCUMENT/ACTION_OPEN_DOCUMENT_TREE) that ManualEntry-
 * Screen/LinkScreen used before - eliminates both the repeated per-folder
 * "Allow Mofy to access folder?" prompt and the whole class of SAF quirks
 * (DownloadStorageProvider's "raw:" documents rejecting reads once their
 * picker callback returns) this session's playback fix had to work around.
 *
 * [pickFolder] selects the browsing mode: false picks a single file
 * (tapping a file returns it immediately), true picks a directory (a "Use
 * this folder" bar at the bottom confirms the currently browsed directory).
 */
@Composable
fun FileBrowserScreen(
    pickFolder: Boolean,
    fileFilter: (File) -> Boolean = { true },
    onPick: (File) -> Unit,
    onCancel: () -> Unit,
) {
    var currentDir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    var query by remember { mutableStateOf("") }

    val entries = remember(currentDir) {
        currentDir.listFiles()
            // Dotfiles - hidden by convention, and Android's own trash
            // (files like ".trashed-<timestamp>-<name>" left behind by the
            // Files app / MediaStore trash) shows up here with nothing else
            // marking it as trash - skip anything starting with "." rather
            // than special-case that one prefix.
            ?.filter { !it.name.startsWith(".") }
            ?.filter { it.isDirectory || fileFilter(it) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
    }
    val filteredEntries = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            val parent = currentDir.parentFile
            if (parent != null && currentDir != Environment.getExternalStorageDirectory()) {
                Box(
                    modifier = Modifier.clickable { currentDir = parent; query = "" }.padding(8.dp),
                ) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Up a folder")
                }
            } else {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Text(
                currentDir.name.ifEmpty { "Storage" },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search this folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredEntries) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) {
                                currentDir = entry
                            } else {
                                onPick(entry)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        if (entry.isDirectory) AppIcons.VideoLibrary else AppIcons.Movie,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (pickFolder) {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Button(onClick = { onPick(currentDir) }, shape = MaterialTheme.shapes.small) {
                    Text("Use this folder")
                }
            }
        }
    }
}

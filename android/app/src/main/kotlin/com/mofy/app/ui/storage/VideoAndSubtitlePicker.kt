package com.mofy.app.ui.storage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

enum class VideoRole { MOVIE, SUBTITLE }
private enum class BrowserMode { SINGLE_VIDEO, SINGLE_SUBTITLE, FOLDER }

/**
 * Single reusable "pick a video, optionally a subtitle" UI block, backed by
 * the in-app java.io.File browser (FileBrowserScreen) and
 * MANAGE_EXTERNAL_STORAGE - not the SAF document picker, which was
 * confirmed on a real device to stop granting read access by Play/Save
 * time on a different screen, for more than one SAF provider, regardless
 * of takePersistableUriPermission. Was duplicated across ManualEntryScreen
 * and LinkScreen with its own copy of this exact bug before being pulled
 * out here - fix it once, both callers get it.
 *
 * Drop this directly into a Column; when the in-app browser is open, it
 * takes over the whole screen (the caller's other content should not also
 * render then - see [isBrowsing]).
 *
 * @param fileUrl current video file:// path, "" if none picked
 * @param fileDisplayName current video filename for display
 * @param subtitleUrl current subtitle file:// path, null if none picked
 * @param subtitleDisplayName current subtitle filename for display
 * @param onVideoPicked called with (file:// path, filename) when a video is picked
 * @param onVideoCleared called when "Change" is tapped on the video row
 * @param onSubtitlePicked called with (file:// path, filename) when a subtitle is picked
 * @param onSubtitleCleared called when "Change" is tapped on the subtitle row
 * @param isBrowsing exposes whether the full-screen file browser is currently open,
 *   so the caller can skip rendering its own content underneath
 */
@Composable
fun VideoAndSubtitlePicker(
    fileUrl: String,
    fileDisplayName: String?,
    subtitleUrl: String?,
    subtitleDisplayName: String?,
    onVideoPicked: (fileUrl: String, displayName: String) -> Unit,
    onVideoCleared: () -> Unit,
    onSubtitlePicked: (fileUrl: String, displayName: String) -> Unit,
    onSubtitleCleared: () -> Unit,
    isBrowsing: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var pickingRole by remember { mutableStateOf<VideoRole?>(null) }
    var browserMode by remember { mutableStateOf<BrowserMode?>(null) }
    var pendingBrowserMode by remember { mutableStateOf<BrowserMode?>(null) }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasAllFilesAccess()) browserMode = pendingBrowserMode
        pendingBrowserMode = null
    }
    fun openBrowser(mode: BrowserMode) {
        if (hasAllFilesAccess()) {
            browserMode = mode
        } else {
            pendingBrowserMode = mode
            allFilesAccessLauncher.launch(requestAllFilesAccessIntent(context))
        }
    }

    isBrowsing(browserMode != null)
    if (browserMode != null) {
        // A full-screen Dialog, not an inline panel - it renders in its own
        // window, escaping the caller's scrollable Column entirely (which
        // is also why fillMaxSize works fine here despite that column
        // otherwise disallowing it), giving a real full-screen browser
        // experience close to the system document picker instead of a
        // cramped in-place panel.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browserMode = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FileBrowserScreen(
                pickFolder = browserMode == BrowserMode.FOLDER,
                onPick = { file ->
                    when (browserMode) {
                        BrowserMode.SINGLE_VIDEO -> onVideoPicked(Uri.fromFile(file).toString(), file.name)
                        BrowserMode.SINGLE_SUBTITLE -> onSubtitlePicked(Uri.fromFile(file).toString(), file.name)
                        BrowserMode.FOLDER -> folderFiles = file.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
                        null -> Unit
                    }
                    browserMode = null
                },
                onCancel = { browserMode = null },
            )
        }
    }

    fun pickFromFolder(file: File) {
        when (pickingRole) {
            VideoRole.MOVIE -> onVideoPicked(Uri.fromFile(file).toString(), file.name)
            VideoRole.SUBTITLE -> onSubtitlePicked(Uri.fromFile(file).toString(), file.name)
            null -> Unit
        }
        pickingRole = null
        // folderFiles deliberately stays populated (not cleared) - picking
        // one role (movie) shouldn't hide the folder listing before the
        // other role (subtitle) has a chance to be picked from the same
        // folder too.
    }

    if (fileUrl.isBlank() || subtitleUrl.isNullOrBlank()) {
        OutlinedButton(
            onClick = { openBrowser(BrowserMode.FOLDER) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect folder")
        }
    }
    if (folderFiles.isNotEmpty()) {
        Text(
            "Folder contents - pick a role below, then tap a file",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { pickingRole = if (pickingRole == VideoRole.MOVIE) null else VideoRole.MOVIE },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (pickingRole == VideoRole.MOVIE) "Tap a file below" else "Set movie")
            }
            OutlinedButton(
                onClick = { pickingRole = if (pickingRole == VideoRole.SUBTITLE) null else VideoRole.SUBTITLE },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (pickingRole == VideoRole.SUBTITLE) "Tap a file below" else "Set subtitle")
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
            items(folderFiles) { file ->
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pickingRole != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = pickingRole != null) { pickFromFolder(file) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }

    Text("Video", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    if (fileUrl.isNotBlank()) {
        PickedFileRow(displayName = fileDisplayName, onChange = onVideoCleared)
    } else {
        OutlinedButton(
            onClick = { openBrowser(BrowserMode.SINGLE_VIDEO) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import video")
        }
    }

    Text("Subtitles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    if (!subtitleUrl.isNullOrBlank()) {
        PickedFileRow(displayName = subtitleDisplayName, onChange = onSubtitleCleared)
    } else {
        OutlinedButton(
            onClick = { openBrowser(BrowserMode.SINGLE_SUBTITLE) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import subtitles")
        }
    }
}

@Composable
private fun PickedFileRow(displayName: String?, onChange: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(displayName ?: "Selected file", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onChange, shape = MaterialTheme.shapes.small) {
            Text("Change")
        }
    }
}

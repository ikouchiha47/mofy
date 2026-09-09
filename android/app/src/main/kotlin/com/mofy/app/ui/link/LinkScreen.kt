package com.mofy.app.ui.link

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mofy.app.data.library.LibraryLink
import com.mofy.app.ui.icons.AppIcons
import com.mofy.app.ui.storage.FileBrowserScreen
import com.mofy.app.ui.storage.hasAllFilesAccess
import com.mofy.app.ui.storage.requestAllFilesAccessIntent
import java.io.File

private enum class RoleTarget { MOVIE, SUBTITLE, SUBTITLE2 }
private enum class BrowserMode { SINGLE_FILE, FOLDER, YOUTUBE }

/**
 * Points Mofy at a file the user already downloaded elsewhere - never
 * downloads anything itself, see ADR 0004. A single file picked is the
 * link, done; a folder picked lists its files and the user explicitly
 * assigns which one is the movie vs. subtitles - no extension sniffing,
 * see ADR 0004's "Alternatives considered". Existing links (if any) show
 * first, with the pick-a-new-one UI always available below - re-opening
 * this screen for an already-linked item shouldn't look identical to a
 * never-linked one.
 *
 * Picking is backed by an in-app java.io.File browser (FileBrowserScreen)
 * and MANAGE_EXTERNAL_STORAGE, not the SAF document picker - confirmed on
 * a real device, repeatedly, that a SAF content:// uri stops being
 * readable by Save time on this screen (SecurityException demanding fresh
 * ACTION_OPEN_DOCUMENT access) for more than one SAF provider, regardless
 * of takePersistableUriPermission.
 */
@Composable
fun LinkScreen(
    contentPadding: PaddingValues,
    existingLinks: List<LibraryLink> = emptyList(),
    onSetActive: ((Long) -> Unit)? = null,
    onSaveSingleFile: (Uri) -> Unit,
    onSaveFolderLink: (movie: Uri, subtitle: Uri?, subtitle2: Uri?) -> Unit,
    // Null hides the "Link from YouTube" option entirely - not every
    // LinkScreen call site is ready to handle it yet.
    onSaveYoutubeLink: ((videoId: String, resolution: String, title: String, thumbnailUrl: String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var folderPicked by remember { mutableStateOf(false) }
    var movieFile by remember { mutableStateOf<File?>(null) }
    var subtitleFile by remember { mutableStateOf<File?>(null) }
    var subtitle2File by remember { mutableStateOf<File?>(null) }
    var pickingRole by remember { mutableStateOf<RoleTarget?>(null) }
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

    if (browserMode == BrowserMode.YOUTUBE) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browserMode = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            com.mofy.app.ui.link.YoutubeLinkPicker(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onPicked = { videoId, resolution, title, thumbnailUrl ->
                    browserMode = null
                    onSaveYoutubeLink?.invoke(videoId, resolution, title, thumbnailUrl)
                },
                onCancel = { browserMode = null },
            )
        }
    } else if (browserMode != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browserMode = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FileBrowserScreen(
                pickFolder = browserMode == BrowserMode.FOLDER,
                onPick = { file ->
                    when (browserMode) {
                        BrowserMode.SINGLE_FILE -> onSaveSingleFile(Uri.fromFile(file))
                        BrowserMode.FOLDER -> {
                            folderFiles = file.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
                            folderPicked = true
                        }
                        BrowserMode.YOUTUBE, null -> Unit
                    }
                    browserMode = null
                },
                onCancel = { browserMode = null },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        if (existingLinks.isNotEmpty()) {
            Text("Linked", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            existingLinks.forEach { link ->
                ExistingLinkRow(link = link, onClick = { if (!link.isActive) onSetActive?.invoke(link.id) })
            }
            Spacer(modifier = Modifier.padding(top = 8.dp))
        }

        if (!folderPicked) {
            Text(
                if (existingLinks.isEmpty()) "Link another file" else "Link another version",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = if (existingLinks.isNotEmpty()) 8.dp else 0.dp, bottom = 4.dp),
            )
            Text(
                "Point Mofy at a file you've already downloaded (with another app). Mofy doesn't download anything itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            PickRow(label = "Pick a single video file") { openBrowser(BrowserMode.SINGLE_FILE) }
            PickRow(label = "Pick a folder", sub = "choose files inside next") { openBrowser(BrowserMode.FOLDER) }
            if (onSaveYoutubeLink != null) {
                PickRow(label = "Link from YouTube", sub = "search, pick a resolution") { browserMode = BrowserMode.YOUTUBE }
            }
        } else {
            Text("Folder contents", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(folderFiles) { file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = pickingRole != null) {
                                when (pickingRole) {
                                    RoleTarget.MOVIE -> movieFile = file
                                    RoleTarget.SUBTITLE -> subtitleFile = file
                                    RoleTarget.SUBTITLE2 -> subtitle2File = file
                                    null -> Unit
                                }
                                pickingRole = null
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }

            Text("Assign roles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
            RoleRow(
                label = "Movie file",
                fileName = movieFile?.name,
                picking = pickingRole == RoleTarget.MOVIE,
                onPick = { pickingRole = if (pickingRole == RoleTarget.MOVIE) null else RoleTarget.MOVIE },
            )
            RoleRow(
                label = "Subtitles",
                fileName = subtitleFile?.name,
                optional = true,
                picking = pickingRole == RoleTarget.SUBTITLE,
                onPick = { pickingRole = if (pickingRole == RoleTarget.SUBTITLE) null else RoleTarget.SUBTITLE },
            )
            RoleRow(
                label = "Subtitles 2",
                fileName = subtitle2File?.name,
                optional = true,
                picking = pickingRole == RoleTarget.SUBTITLE2,
                onPick = { pickingRole = if (pickingRole == RoleTarget.SUBTITLE2) null else RoleTarget.SUBTITLE2 },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = { folderPicked = false; movieFile = null; subtitleFile = null; subtitle2File = null },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val movie = movieFile ?: return@Button
                        onSaveFolderLink(
                            Uri.fromFile(movie),
                            subtitleFile?.let { Uri.fromFile(it) },
                            subtitle2File?.let { Uri.fromFile(it) },
                        )
                    },
                    enabled = movieFile != null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) { Text("Save link") }
            }
        }
    }
}

@Composable
private fun ExistingLinkRow(link: LibraryLink, onClick: () -> Unit) {
    val fileName = link.movieUri.substringAfterLast('/')
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(link.label ?: fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (link.subtitleUri != null) {
                Text("+ subtitles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (link.isActive) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3ECF8E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Active", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        } else {
            Text("Tap to make active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickRow(label: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoleRow(label: String, fileName: String?, optional: Boolean = false, picking: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        Text(
            fileName ?: if (optional) "Optional — none picked" else "Not picked",
            style = MaterialTheme.typography.bodySmall,
            color = if (fileName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(onClick = onPick, shape = MaterialTheme.shapes.small) {
            Text(if (picking) "Tap a file above" else if (fileName != null) "Change" else "Pick")
        }
    }
}

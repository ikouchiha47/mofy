package com.mofy.app.ui.link

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.mofy.app.data.library.LibraryLink

private enum class RoleTarget { MOVIE, SUBTITLE, SUBTITLE2 }

/**
 * Points Mofy at a file the user already downloaded elsewhere - never
 * downloads anything itself, see ADR 0004. A single file picked is the
 * link, done; a folder picked lists its files and the user explicitly
 * assigns which one is the movie vs. subtitles - no extension sniffing,
 * see ADR 0004's "Alternatives considered". Existing links (if any) show
 * first, with the pick-a-new-one UI always available below - re-opening
 * this screen for an already-linked item shouldn't look identical to a
 * never-linked one.
 */
@Composable
fun LinkScreen(
    contentPadding: PaddingValues,
    existingLinks: List<LibraryLink> = emptyList(),
    onSetActive: ((Long) -> Unit)? = null,
    onSaveSingleFile: (Uri) -> Unit,
    onSaveFolderLink: (movie: Uri, subtitle: Uri?, subtitle2: Uri?) -> Unit,
) {
    val context = LocalContext.current
    var folderFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var folderPicked by remember { mutableStateOf(false) }
    var movieUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }
    var subtitle2Uri by remember { mutableStateOf<Uri?>(null) }
    var pickingRole by remember { mutableStateOf<RoleTarget?>(null) }

    val mediaPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, mediaPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(mediaPermission)
        }
    }

    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(uri)
        onSaveSingleFile(uri)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        persist(treeUri)
        val docFile = DocumentFile.fromTreeUri(context, treeUri)
        folderFiles = docFile?.listFiles()?.filter { it.isFile }.orEmpty()
        folderPicked = true
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
            PickRow(label = "Pick a single video file") { pickFile.launch(arrayOf("video/*")) }
            PickRow(label = "Pick a folder", sub = "choose files inside next") { pickFolder.launch(null) }
        } else {
            Text("Folder contents", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(folderFiles) { file ->
                    val name = file.name ?: "unknown"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = pickingRole != null) {
                                when (pickingRole) {
                                    RoleTarget.MOVIE -> movieUri = file.uri
                                    RoleTarget.SUBTITLE -> subtitleUri = file.uri
                                    RoleTarget.SUBTITLE2 -> subtitle2Uri = file.uri
                                    null -> Unit
                                }
                                pickingRole = null
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }

            Text("Assign roles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
            RoleRow(
                label = "Movie file",
                fileName = folderFiles.find { it.uri == movieUri }?.name,
                picking = pickingRole == RoleTarget.MOVIE,
                onPick = { pickingRole = if (pickingRole == RoleTarget.MOVIE) null else RoleTarget.MOVIE },
            )
            RoleRow(
                label = "Subtitles",
                fileName = folderFiles.find { it.uri == subtitleUri }?.name,
                optional = true,
                picking = pickingRole == RoleTarget.SUBTITLE,
                onPick = { pickingRole = if (pickingRole == RoleTarget.SUBTITLE) null else RoleTarget.SUBTITLE },
            )
            RoleRow(
                label = "Subtitles 2",
                fileName = folderFiles.find { it.uri == subtitle2Uri }?.name,
                optional = true,
                picking = pickingRole == RoleTarget.SUBTITLE2,
                onPick = { pickingRole = if (pickingRole == RoleTarget.SUBTITLE2) null else RoleTarget.SUBTITLE2 },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = { folderPicked = false; movieUri = null; subtitleUri = null; subtitle2Uri = null },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = { movieUri?.let { onSaveFolderLink(it, subtitleUri, subtitle2Uri) } },
                    enabled = movieUri != null,
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
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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

package com.mofy.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.library.LibrarySource
import com.mofy.app.data.library.PosterSource
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResult
import com.mofy.app.ui.components.CategorySegmentedControl
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Fallback for titles TMDB doesn't have at all - see ADR 0004. Only Title
 * is required; poster can come from a poster-only TMDB re-search or a local
 * upload, and falls back to a plain placeholder if neither is picked -
 * never a blocking field.
 */
@Composable
fun ManualEntryScreen(
    contentPadding: PaddingValues,
    onSave: (LibraryItem, fileUrl: String?) -> Unit,
    // Carried over from Import's "Can't find it? Enter details manually"
    // link - the filename-derived guess and the file you already picked
    // there, so neither has to be redone from scratch on this screen.
    initialTitle: String = "",
    initialFileUrl: String? = null,
    initialMediaType: MediaType = MediaType.MOVIE,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tmdbRepository = remember { TmdbRepository() }

    var title by remember { mutableStateOf(initialTitle) }
    var year by remember { mutableStateOf("") }
    var mediaType by remember { mutableStateOf(initialMediaType) }
    var genresText by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf("") }

    var posterUrl by remember { mutableStateOf<String?>(null) }
    var posterPath by remember { mutableStateOf<String?>(null) }
    var localPosterUri by remember { mutableStateOf<Uri?>(null) }
    var posterSource by remember { mutableStateOf(PosterSource.NONE) }

    // A picked file's display name (for showing what's selected) - the raw
    // fileUrl (a content:// URI, not a path) is what actually gets saved,
    // see the Save button below.
    var fileUrl by remember { mutableStateOf(initialFileUrl ?: "") }
    var fileDisplayName by remember {
        mutableStateOf(initialFileUrl?.let { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it))?.name }.getOrNull() })
    }
    var folderFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }

    var posterSearchResults by remember { mutableStateOf<List<MediaResult>>(emptyList()) }
    var posterSearchLoading by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        localPosterUri = uri
        posterUrl = uri.toString()
        posterSource = PosterSource.UPLOADED
        posterSearchResults = emptyList()
    }

    // Same SAF pattern as LinkScreen's Import flow - a raw typed filesystem
    // path (the old "File URL" text field) doesn't work with scoped
    // storage on modern Android anyway, so this wasn't just a UX rough
    // edge, it was largely non-functional outside a handful of legacy paths.
    //
    // The picked content:// uri is NOT kept as fileUrl - confirmed on a real
    // device that DownloadStorageProvider's "raw:" documents reject reads
    // (openFileDescriptor AND openInputStream) once this picker callback has
    // returned, regardless of takePersistableUriPermission - this provider
    // doesn't honor the normal persisted-grants table for its raw:
    // children. The access this callback has right now is resolved to a
    // stable playable uri immediately, while it's still valid:
    // com.mofy.app.playback.resolvePlayableUri first tries MediaStore (the
    // uri real media apps use for Downloads/gallery files, which
    // READ_MEDIA_VIDEO covers reliably), falling back to copying the bytes
    // into app-private storage for a file MediaStore hasn't indexed yet.
    val pickVideoFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pickedName = DocumentFile.fromSingleUri(context, uri)?.name
        folderFiles = emptyList()
        fileDisplayName = pickedName
        fileUrl = ""
        coroutineScope.launch {
            fileUrl = com.mofy.app.playback.resolvePlayableUri(context, uri, pickedName)
        }
    }
    val pickVideoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fileUrl = ""
        fileDisplayName = null
        folderFiles = DocumentFile.fromTreeUri(context, treeUri)?.listFiles()?.filter { it.isFile }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column {
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = "Poster",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(110.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .width(110.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No poster yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (title.isBlank()) return@OutlinedButton
                        posterSearchLoading = true
                        coroutineScope.launch {
                            val result = when (mediaType) {
                                MediaType.MOVIE -> tmdbRepository.searchMovies(title)
                                MediaType.TV -> tmdbRepository.searchTv(title)
                            }
                            posterSearchResults = (result as? TmdbResult.Success)?.data.orEmpty()
                            posterSearchLoading = false
                        }
                    },
                    enabled = title.isNotBlank() && !posterSearchLoading,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.width(110.dp).padding(top = 8.dp),
                ) {
                    Text("🔍 Search TMDB", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.width(110.dp).padding(top = 6.dp),
                ) {
                    Text("📁 Upload image", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("Year") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                CategorySegmentedControl(
                    selected = mediaType,
                    onSelect = { mediaType = it },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        if (posterSearchLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(24.dp))
        } else if (posterSearchResults.isNotEmpty()) {
            Text(
                "Pick a poster",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(posterSearchResults.filter { it.posterUrl != null }) { candidate ->
                    AsyncImage(
                        model = candidate.posterUrl,
                        contentDescription = candidate.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(64.dp)
                            .height(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                posterUrl = candidate.posterUrl
                                posterPath = candidate.posterPath
                                localPosterUri = null
                                posterSource = PosterSource.TMDB
                            },
                    )
                }
            }
        }

        OutlinedTextField(
            value = genresText,
            onValueChange = { genresText = it },
            label = { Text("Genres (comma separated)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        OutlinedTextField(
            value = overview,
            onValueChange = { overview = it },
            label = { Text("Overview (optional)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        Text(
            "Video file (optional)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
        if (fileUrl.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                // fileDisplayName can fail to resolve (name query returning
                // null, a URI shape DocumentFile.fromSingleUri doesn't
                // recognize) without fileUrl itself being unset - gating
                // this row on fileUrl (not fileDisplayName) is what fixes
                // that; "Selected file" covers the case fileDisplayName
                // never resolved instead of silently falling through to the
                // picker buttons while a file is actually already chosen.
                Text(fileDisplayName ?: "Selected file", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { fileUrl = ""; fileDisplayName = null },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Change")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pickVideoFile.launch(arrayOf("video/*")) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import single video file")
                }
                OutlinedButton(
                    onClick = { pickVideoFolder.launch(null) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Connect to directory")
                }
            }
            if (folderFiles.isNotEmpty()) {
                Text(
                    "Pick a file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    items(folderFiles) { file ->
                        Text(
                            file.name ?: "unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fileUrl = file.uri.toString()
                                    fileDisplayName = file.name
                                    folderFiles = emptyList()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Text(
            "If set, this file is linked and activated immediately - useful for manual testing (e.g. Watch Together) without a separate Link step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            "Poster falls back to a placeholder if nothing is picked - nothing here is required except the title.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        Button(
            onClick = {
                onSave(
                    LibraryItem(
                        id = UUID.randomUUID().toString(),
                        tmdbId = null,
                        mediaType = mediaType.name,
                        title = title.trim(),
                        originalTitle = null,
                        romanizedOriginalTitle = null,
                        overview = overview.trim(),
                        posterPath = posterPath,
                        localPosterUri = localPosterUri?.toString(),
                        posterSource = posterSource.name,
                        year = year.trim().ifBlank { null },
                        genreIds = "",
                        genresManual = genresText.trim().ifBlank { null },
                        voteAverage = 0.0,
                        runtime = null,
                        tagline = null,
                        source = LibrarySource.MANUAL.name,
                        addedAtEpochMillis = System.currentTimeMillis(),
                        detailSyncedAtEpochMillis = null,
                        feedback = null,
                    ),
                    fileUrl.trim().ifBlank { null },
                )
            },
            enabled = title.isNotBlank(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text("Save to library")
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

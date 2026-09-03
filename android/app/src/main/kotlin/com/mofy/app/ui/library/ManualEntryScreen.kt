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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.library.LibrarySource
import com.mofy.app.data.library.PosterSource
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResult
import com.mofy.app.ui.components.CategorySegmentedControl
import com.mofy.app.ui.icons.AppIcons
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
    onSave: (LibraryItem, fileUrl: String?, subtitleUrl: String?) -> Unit,
    genreRepository: com.mofy.app.data.tmdb.GenreRepository,
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
    // fileUrl (a file:// path) is what actually gets saved, see the Save
    // button below.
    var fileUrl by remember { mutableStateOf(initialFileUrl ?: "") }
    var fileDisplayName by remember { mutableStateOf(initialFileUrl?.substringAfterLast('/')) }
    var subtitleUrl by remember { mutableStateOf<String?>(null) }
    var subtitleDisplayName by remember { mutableStateOf<String?>(null) }
    // True while `title` is just a filename guess - a fresh video pick may
    // overwrite it (and clear overview/genres/poster along with it); false
    // once you type your own title or confirm a TMDB match, so a later
    // video pick leaves everything alone.
    var titleIsGuess by remember { mutableStateOf(initialTitle.isBlank()) }

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
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { pickImage.launch(arrayOf("image/*")) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            AppIcons.Add,
                            contentDescription = "Upload image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "No poster yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                    Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Tmdb", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; titleIsGuess = false },
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
                                // candidate already carries year/overview/
                                // genreIds from the same TMDB search that
                                // fetched the poster - filling these in too
                                // is what makes tapping a poster act as a
                                // real "confirm this match", not just a
                                // poster swap.
                                title = candidate.title
                                titleIsGuess = false
                                year = candidate.year ?: year
                                overview = candidate.overview
                                coroutineScope.launch {
                                    genresText = genreRepository.resolveNames(candidate.genreIds).joinToString(", ")
                                }
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
            "Video & subtitles (optional)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
        com.mofy.app.ui.storage.VideoAndSubtitlePicker(
            fileUrl = fileUrl,
            fileDisplayName = fileDisplayName,
            subtitleUrl = subtitleUrl,
            subtitleDisplayName = subtitleDisplayName,
            onVideoPicked = { url, name ->
                fileUrl = url
                fileDisplayName = name
                // titleIsGuess false means you typed a title or confirmed a
                // TMDB match - a new video pick must not touch title or the
                // metadata that came with it.
                if (titleIsGuess) {
                    title = com.mofy.app.data.library.guessTitleFromFileName(name)
                    year = ""
                    overview = ""
                    genresText = ""
                    posterUrl = null
                    posterPath = null
                    localPosterUri = null
                    posterSource = PosterSource.NONE
                }
            },
            onVideoCleared = { fileUrl = ""; fileDisplayName = null },
            onSubtitlePicked = { url, name -> subtitleUrl = url; subtitleDisplayName = name },
            onSubtitleCleared = { subtitleUrl = null; subtitleDisplayName = null },
        )

        Text(
            "If set, this file is linked and activated immediately - useful for manual testing (e.g. Watch Together) without a separate Link step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
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
                    subtitleUrl?.trim()?.ifBlank { null },
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

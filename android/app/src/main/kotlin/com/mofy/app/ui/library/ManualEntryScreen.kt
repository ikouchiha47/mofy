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
    onSave: (LibraryItem) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tmdbRepository = remember { TmdbRepository() }

    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var mediaType by remember { mutableStateOf(MediaType.MOVIE) }
    var genresText by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf("") }

    var posterUrl by remember { mutableStateOf<String?>(null) }
    var posterPath by remember { mutableStateOf<String?>(null) }
    var localPosterUri by remember { mutableStateOf<Uri?>(null) }
    var posterSource by remember { mutableStateOf(PosterSource.NONE) }

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

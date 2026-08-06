package com.mofy.app.ui.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType

@Composable
fun ConfirmMatchScreen(
    contentPadding: PaddingValues,
    extractedTitle: String,
    mediaType: MediaType,
    onConfirm: (MediaResult) -> Unit,
    onSaveToLibrary: (List<MediaResult>) -> Unit,
    viewModel: ConfirmMatchViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(extractedTitle, mediaType) {
        viewModel.search(extractedTitle, mediaType)
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        // Extracted-title banner + locked-category pill - see ADR 0003.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            Text("Extracted from page:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("\"$extractedTitle\"", style = MaterialTheme.typography.titleMedium)
            val categoryLabel = if (mediaType == MediaType.MOVIE) "movies" else "TV shows"
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    "searching $categoryLabel ▸ category locked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).padding(24.dp))
                uiState.errorMessage != null -> Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                uiState.results.isEmpty() -> Text("No matches found for \"$extractedTitle\".")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.results) { result ->
                ResultCard(
                    result = result,
                    isSelected = result.id == uiState.selected?.id,
                    isCheckedForLibrary = result.id in uiState.selectedForLibrary,
                    onClick = { viewModel.selectResult(result) },
                    onLibraryCheckedChange = { viewModel.toggleLibrarySelection(result) },
                )
            }
        }

        val libraryCount = uiState.selectedForLibrary.size
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val toSave = uiState.results.filter { it.id in uiState.selectedForLibrary }
                    onSaveToLibrary(toSave)
                },
                enabled = libraryCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (libraryCount > 0) "Save to Library ($libraryCount)" else "Save to Library")
            }
            Button(
                onClick = { uiState.selected?.let(onConfirm) },
                enabled = uiState.selected != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("Confirm & Download")
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: MediaResult,
    isSelected: Boolean,
    isCheckedForLibrary: Boolean,
    onClick: () -> Unit,
    onLibraryCheckedChange: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(10.dp).padding(end = 32.dp)) {
        if (result.posterUrl != null) {
            AsyncImage(
                model = result.posterUrl,
                contentDescription = "${result.title} poster",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 54.dp, height = 78.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 78.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${result.year ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                result.overview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        }
        Checkbox(
            checked = isCheckedForLibrary,
            onCheckedChange = { onLibraryCheckedChange() },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

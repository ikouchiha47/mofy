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
import com.mofy.app.ui.components.CategorySegmentedControl

@Composable
fun ConfirmMatchScreen(
    contentPadding: PaddingValues,
    extractedTitle: String,
    mediaType: MediaType,
    onConfirm: (MediaResult) -> Unit,
    onSaveToLibrary: (List<MediaResult>) -> Unit,
    // Imported local files have nothing to download - only Save to Library
    // makes sense there. See LibraryScreen's Import flow.
    showDownloadAction: Boolean = true,
    // Only the Import flow sets this - there's no site/category context for
    // a locally-picked file to lock onto, unlike the WebView flow where the
    // category was already chosen back on Browse.
    onMediaTypeChange: ((MediaType) -> Unit)? = null,
    viewModel: ConfirmMatchViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(extractedTitle, mediaType) {
        viewModel.search(extractedTitle, mediaType)
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (onMediaTypeChange != null) {
            CategorySegmentedControl(selected = mediaType, onSelect = onMediaTypeChange)
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
            val lockLabel = if (onMediaTypeChange == null) " ▸ category locked" else ""
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    "searching $categoryLabel$lockLabel",
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
                    isChecked = result.id in uiState.checkedIds,
                    onToggle = { viewModel.toggleChecked(result) },
                )
            }
        }

        val checkedCount = uiState.checkedIds.size
        val confirmTarget = uiState.confirmTarget
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val toSave = uiState.results.filter { it.id in uiState.checkedIds }
                    onSaveToLibrary(toSave)
                },
                enabled = checkedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (checkedCount > 0) "Save to Library ($checkedCount)" else "Save to Library")
            }
            if (showDownloadAction) {
                Button(
                    // Only makes sense for exactly one selection - a magnet can
                    // only ever be one thing, see ConfirmMatchUiState.confirmTarget.
                    onClick = { confirmTarget?.let(onConfirm) },
                    enabled = confirmTarget != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Confirm & Download")
                }
            }
        }
    }
    }
}

@Composable
private fun ResultCard(result: MediaResult, isChecked: Boolean, onToggle: () -> Unit) {
    val borderColor = if (isChecked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle),
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
            checked = isChecked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

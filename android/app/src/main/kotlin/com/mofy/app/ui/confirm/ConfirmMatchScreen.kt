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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
                    onClick = { viewModel.selectResult(result) },
                )
            }
        }

        Button(
            onClick = { uiState.selected?.let(onConfirm) },
            enabled = uiState.selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Confirm & Start Download")
        }
    }
}

@Composable
private fun ResultCard(result: MediaResult, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
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
}

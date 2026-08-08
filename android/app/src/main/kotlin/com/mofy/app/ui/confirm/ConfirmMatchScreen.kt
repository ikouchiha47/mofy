package com.mofy.app.ui.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Magnet taps auto-search immediately (dn= is usually reliable). Import
    // doesn't - a filename-derived guess is much shakier, so the user edits
    // the title and explicitly triggers the TMDB search instead of eating a
    // bad-guess round-trip before they've even seen the field.
    autoSearch: Boolean = true,
    // When false, results use the existing radio (magnetMatchId) as a
    // single-select instead of checkboxes - no separate selection concept
    // needed. Import sets this: a picked file can only unambiguously link
    // to one saved item, so multi-select there just creates ambiguity.
    allowMultiSelect: Boolean = true,
    // Only Library's "Add manually" flow sets this - the escape hatch for
    // titles TMDB doesn't have at all, see ADR 0004.
    onManualEntry: (() -> Unit)? = null,
    viewModel: ConfirmMatchViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var editableTitle by remember(extractedTitle) { mutableStateOf(extractedTitle) }

    if (autoSearch) {
        LaunchedEffect(extractedTitle, mediaType) {
            viewModel.search(extractedTitle, mediaType)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (onMediaTypeChange != null) {
            CategorySegmentedControl(selected = mediaType, onSelect = onMediaTypeChange)
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (autoSearch) {
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
        } else {
            // Best-effort filename guess, editable - regex cleanup gets you
            // close, not exact, so fix it up before searching.
            OutlinedTextField(
                value = editableTitle,
                onValueChange = { editableTitle = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.search(editableTitle, mediaType) },
                enabled = editableTitle.isNotBlank(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text("🔍 Search TMDB")
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).padding(24.dp))
                uiState.errorMessage != null -> Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                uiState.results.isEmpty() && uiState.hasSearched -> Column {
                    val currentTabLabel = if (mediaType == MediaType.MOVIE) "Movies" else "TV Shows"
                    Text("No matches found for \"$editableTitle\" in $currentTabLabel.")
                    if (onMediaTypeChange != null) {
                        val otherType = if (mediaType == MediaType.MOVIE) MediaType.TV else MediaType.MOVIE
                        val otherLabel = if (otherType == MediaType.MOVIE) "Movies" else "TV Shows"
                        Text(
                            "Try the $otherLabel tab instead?",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable {
                                    onMediaTypeChange(otherType)
                                    viewModel.search(editableTitle, otherType)
                                },
                        )
                    }
                }
            }
        }

        if (onManualEntry != null && !autoSearch) {
            Text(
                "Can't find it? Enter details manually →",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clickable(onClick = onManualEntry),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.results) { result ->
                ResultCard(
                    result = result,
                    allowMultiSelect = allowMultiSelect,
                    isChecked = result.id in uiState.checkedIds,
                    onToggle = { viewModel.toggleChecked(result) },
                    isSelected = (showDownloadAction || !allowMultiSelect) && result.id == uiState.magnetMatchId,
                    onSelect = if (showDownloadAction || !allowMultiSelect) {
                        { viewModel.selectMagnetMatch(result) }
                    } else {
                        null
                    },
                )
            }
        }

        val checkedCount = uiState.checkedIds.size
        val confirmTarget = uiState.confirmTarget
        val saveTargets = if (allowMultiSelect) {
            uiState.results.filter { it.id in uiState.checkedIds }
        } else {
            listOfNotNull(confirmTarget)
        }
        val saveEnabled = if (allowMultiSelect) checkedCount > 0 else confirmTarget != null
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onSaveToLibrary(saveTargets) },
                enabled = saveEnabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (allowMultiSelect && checkedCount > 0) "Save to Library ($checkedCount)" else "Save to Library")
            }
            if (showDownloadAction) {
                Button(
                    // Only makes sense for exactly one selection - a magnet can
                    // only ever be one thing, see ConfirmMatchUiState.confirmTarget.
                    onClick = { confirmTarget?.let(onConfirm) },
                    enabled = confirmTarget != null,
                    shape = MaterialTheme.shapes.small,
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
private fun ResultCard(
    result: MediaResult,
    allowMultiSelect: Boolean,
    isChecked: Boolean,
    onToggle: () -> Unit,
    isSelected: Boolean,
    onSelect: (() -> Unit)?,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = if (allowMultiSelect) onToggle else { onSelect ?: onToggle }),
    ) {
        Row(modifier = Modifier.padding(10.dp).padding(end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onSelect != null) {
            RadioButton(selected = isSelected, onClick = onSelect)
        }
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
            if (result.originalTitle != null) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    if (result.romanizedOriginalTitle != null) {
                        com.mofy.app.ui.components.Tag(result.romanizedOriginalTitle)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    com.mofy.app.ui.components.Tag(result.originalTitle)
                }
            }
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
        if (allowMultiSelect) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

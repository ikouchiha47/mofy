package com.mofy.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.Tag
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Import entry point + the actual library listing - was import-button-only,
 * with nothing showing what was actually in the library outside Home's
 * "Recently Added" row. Reuses Detail's Link picker UI for import (see
 * PushedRoute.IMPORT_LINK in MainActivity). Each row has a delete action -
 * saveConfirmedMatch merges on (tmdbId, mediaType) rather than erroring, so
 * this isn't strictly needed to fix a crash, but it's the clean way to
 * fully remove and cleanly re-import something instead of it silently
 * merging into the existing row.
 *
 * Filtering is split in two, deliberately: Type is a segmented control
 * (mutually exclusive, few options, room for e.g. Music later) - Genre (and
 * any future filter dimension) lives behind a "Filters" sheet instead of a
 * second permanent row, since that's the one that'll actually grow.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    libraryDao: LibraryDao? = null,
    genreRepository: GenreRepository? = null,
    onImportClick: () -> Unit,
    onAddManuallyClick: () -> Unit = {},
    onItemClick: (LibraryItem) -> Unit = {},
) {
    val items by (libraryDao?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf<MediaType?>(null) }
    var selectedGenreId by remember { mutableStateOf<Int?>(null) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var genreNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(genreRepository) {
        genreNames = genreRepository?.getAllAsMap() ?: emptyMap()
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchMatchedIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(searchQuery, libraryDao) {
        searchMatchedIds = if (searchQuery.isBlank()) {
            null
        } else {
            libraryDao?.searchLibrary(searchQuery)?.toSet() ?: emptySet()
        }
    }

    val availableGenreIds = remember(items, genreNames) {
        items.flatMap { it.resolvedGenreIds }.distinct().filter { it in genreNames }.sortedBy { genreNames[it] }
    }
    val filtered = remember(items, selectedType, selectedGenreId, searchMatchedIds) {
        items.filter { item ->
            (selectedType == null || item.mediaType == selectedType?.name) &&
                (selectedGenreId == null || selectedGenreId in item.resolvedGenreIds) &&
                (searchMatchedIds == null || item.id in searchMatchedIds!!)
        }
    }
    val activeFilterCount = listOfNotNull(selectedGenreId).size

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search title or overview") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(
                onClick = onImportClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Import from device", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = onAddManuallyClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text("Add manually")
            }
        }

        if (items.isNotEmpty()) {
            TypeSegmentedControl(
                selected = selectedType,
                onSelect = { selectedType = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                FilterButton(count = activeFilterCount, onClick = { filterSheetOpen = true })
                if (selectedGenreId != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(
                        label = genreNames[selectedGenreId] ?: "",
                        onRemove = { selectedGenreId = null },
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing in your library yet — head to Browse to find something.",
                    textAlign = TextAlign.Center,
                )
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nothing matches this filter.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn {
                items(filtered, key = { it.id }) { item ->
                    LibraryListRow(
                        item,
                        onClick = { onItemClick(item) },
                        onDelete = { coroutineScope.launch { libraryDao?.deleteLibraryItem(item.id) } },
                    )
                }
            }
        }
    }

    if (filterSheetOpen) {
        var pendingGenreId by remember(selectedGenreId) { mutableStateOf(selectedGenreId) }
        ModalBottomSheet(
            onDismissRequest = { filterSheetOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 14.dp))

                if (availableGenreIds.isNotEmpty()) {
                    Text(
                        "Genre",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableGenreIds.forEach { genreId ->
                            FilterChip(
                                label = genreNames[genreId] ?: return@forEach,
                                selected = pendingGenreId == genreId,
                                onClick = { pendingGenreId = if (pendingGenreId == genreId) null else genreId },
                            )
                        }
                    }
                } else {
                    Text(
                        "No genres to filter by yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp)) {
                    OutlinedButton(
                        onClick = { pendingGenreId = null; selectedGenreId = null; filterSheetOpen = false },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                    Button(
                        onClick = { selectedGenreId = pendingGenreId; filterSheetOpen = false },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun TypeSegmentedControl(selected: MediaType?, onSelect: (MediaType?) -> Unit, modifier: Modifier = Modifier) {
    // Extra segments (e.g. Music) just add another SegmentOption call here later.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentOption("All", selected == null, Modifier.weight(1f)) { onSelect(null) }
        SegmentOption("Movies", selected == MediaType.MOVIE, Modifier.weight(1f)) { onSelect(MediaType.MOVIE) }
        SegmentOption("TV", selected == MediaType.TV, Modifier.weight(1f)) { onSelect(MediaType.TV) }
    }
}

@Composable
private fun SegmentOption(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("Filters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        if (count > 0) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp).padding(start = 2.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove filter", modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryListRow(item: LibraryItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.posterUrl != null) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = "${item.title} poster",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(46.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp, 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.width(8.dp))
                Tag(if (item.mediaType == MediaType.TV.name) "TV" else "Movie")
            }
            Text(
                "Date Added: ${formatDate(item.addedAtEpochMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${item.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

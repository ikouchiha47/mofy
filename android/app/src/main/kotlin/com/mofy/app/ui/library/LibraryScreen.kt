package com.mofy.app.ui.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.ActiveFilterChip
import com.mofy.app.ui.components.FilterButton
import com.mofy.app.ui.components.FilterSidePanel
import com.mofy.app.ui.components.LibraryListRow
import com.mofy.app.ui.components.SelectableListRow
import com.mofy.app.ui.components.TypeSegmentedControl
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
@OptIn(ExperimentalMaterial3Api::class)
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

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
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

        var pendingGenreId by remember(selectedGenreId) { mutableStateOf(selectedGenreId) }
        FilterSidePanel(
            visible = filterSheetOpen,
            onDismiss = { filterSheetOpen = false },
            onClear = { pendingGenreId = null; selectedGenreId = null; filterSheetOpen = false },
            onApply = { selectedGenreId = pendingGenreId; filterSheetOpen = false },
        ) {
            LazyColumn {
                items(availableGenreIds) { genreId ->
                    SelectableListRow(
                        label = genreNames[genreId] ?: "",
                        selected = pendingGenreId == genreId,
                        onClick = { pendingGenreId = if (pendingGenreId == genreId) null else genreId },
                    )
                }
            }
        }
    }
}


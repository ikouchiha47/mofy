package com.mofy.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.ActiveFilterChip
import com.mofy.app.ui.components.FilterButton
import com.mofy.app.ui.components.FilterChip
import com.mofy.app.ui.components.LibraryListRow
import com.mofy.app.ui.components.TypeSegmentedControl
import kotlinx.coroutines.flow.emptyFlow

/**
 * Search across the whole library, reached from Home's search icon - not
 * a separate index, just LibraryDao.searchLibrary (FTS + spellfix1 fuzzy
 * fallback, see LibraryDao) combined with the same Type/Genre filters
 * LibraryScreen uses. Shares its filter UI via ui/components -
 * LibraryFilterControls.kt - rather than duplicating it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    libraryDao: LibraryDao? = null,
    genreRepository: GenreRepository? = null,
    onItemClick: (LibraryItem) -> Unit = {},
) {
    val items by (libraryDao?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())

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
    val focusRequester = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search your library") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

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

        when {
            searchQuery.isBlank() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Search your library by title, overview, or alternate name.", textAlign = TextAlign.Center)
            }
            filtered.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nothing matches \"$searchQuery\".", textAlign = TextAlign.Center)
            }
            else -> LazyColumn {
                items(filtered, key = { it.id }) { item ->
                    LibraryListRow(item, onClick = { onItemClick(item) })
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

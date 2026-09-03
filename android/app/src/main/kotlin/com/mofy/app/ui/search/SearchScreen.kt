package com.mofy.app.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mofy.app.data.catalog.CatalogItem
import com.mofy.app.data.catalog.CatalogRepository
import com.mofy.app.data.catalog.IMDB_GENRES
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.search.FacetDecoder
import com.mofy.app.search.OnDeviceEmbedder
import com.mofy.app.ui.components.ActiveFilterChip
import com.mofy.app.ui.components.FilterButton
import com.mofy.app.ui.components.FilterSidePanel
import com.mofy.app.ui.components.SelectableListRow
import com.mofy.app.ui.components.TypeSegmentedControl
import com.mofy.app.ui.discover.DiscoverRow
import kotlinx.coroutines.delay

/**
 * Search across Discovery + Library, reached from Home's search icon -
 * the same fused query CatalogRepository.semanticSearch() runs for
 * Discover (bundled catalog.db FTS/embedding + library items, matched or
 * unmatched to an embedding), not a library-only lookup. A result whose
 * tconst already starts with "lib:" is an existing library item; the
 * caller (see MainActivity's openCatalogItemDetail) reopens it in place
 * instead of creating a duplicate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    catalogRepository: CatalogRepository? = null,
    embedder: OnDeviceEmbedder? = null,
    facetDecoder: FacetDecoder,
    onItemClick: (CatalogItem) -> Unit = {},
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf<MediaType?>(null) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var filterSheetOpen by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    var results by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    LaunchedEffect(debouncedQuery, catalogRepository, embedder) {
        val repository = catalogRepository
        val activeEmbedder = embedder
        results = if (debouncedQuery.isBlank() || repository == null || activeEmbedder == null) {
            emptyList()
        } else {
            repository.semanticSearch(
                query = debouncedQuery,
                context = context,
                embedder = activeEmbedder,
                facetDecoder = facetDecoder,
            )
        }
    }

    val titleTypeFilter = when (selectedType) {
        MediaType.MOVIE -> "movie"
        MediaType.TV -> "tvSeries"
        null -> null
    }
    val filtered = remember(results, titleTypeFilter, selectedGenre) {
        val genre = selectedGenre
        results.filter { item ->
            val typeOk = titleTypeFilter == null || item.titleType == titleTypeFilter
            val genreOk = genre?.let { item.genres?.contains(it, ignoreCase = true) == true } ?: true
            typeOk && genreOk
        }
    }
    val activeFilterCount = listOfNotNull(selectedGenre).size
    val focusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Discover + Library") },
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

            if (results.isNotEmpty()) {
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
                    if (selectedGenre != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ActiveFilterChip(label = selectedGenre ?: "", onRemove = { selectedGenre = null })
                    }
                }
            }

            when {
                searchQuery.isBlank() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Search Discover and your library by title, overview, or genre.", textAlign = TextAlign.Center)
                }
                filtered.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing matches \"$searchQuery\".", textAlign = TextAlign.Center)
                }
                else -> LazyColumn {
                    items(filtered, key = { it.tconst }) { item ->
                        DiscoverRow(item = item, onAdd = { onItemClick(item) })
                    }
                }
            }
        }

        var pendingGenre by remember(selectedGenre) { mutableStateOf(selectedGenre) }
        FilterSidePanel(
            visible = filterSheetOpen,
            onDismiss = { filterSheetOpen = false },
            onClear = { pendingGenre = null; selectedGenre = null; filterSheetOpen = false },
            onApply = { selectedGenre = pendingGenre; filterSheetOpen = false },
        ) {
            LazyColumn {
                items(IMDB_GENRES) { genre ->
                    SelectableListRow(
                        label = genre,
                        selected = pendingGenre == genre,
                        onClick = { pendingGenre = if (pendingGenre == genre) null else genre },
                    )
                }
            }
        }
    }
}

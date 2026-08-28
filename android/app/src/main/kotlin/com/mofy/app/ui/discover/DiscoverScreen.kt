package com.mofy.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mofy.app.data.catalog.CatalogItem
import com.mofy.app.data.catalog.CatalogRepository
import com.mofy.app.data.catalog.CatalogSort
import com.mofy.app.data.catalog.IMDB_GENRES
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.ActiveFilterChip
import com.mofy.app.ui.components.FilterButton
import com.mofy.app.ui.components.FilterSidePanel
import com.mofy.app.ui.components.SelectableListRow
import com.mofy.app.ui.components.Tag
import com.mofy.app.ui.components.TypeSegmentedControl
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mofy.app.search.FacetDecoder
import com.mofy.app.search.OnDeviceEmbedder
import com.mofy.app.search.RuleBasedFacetDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Browses the bundled IMDb catalog (ml/data/catalog.db - see ml/README.md)
 * for titles not yet in the user's Library. No poster images in the
 * catalog (IMDb-only, no TMDB image fetch to keep the ml/ pipeline free of
 * per-title API calls) - every row uses a placeholder icon instead, same
 * visual slot LibraryListRow already falls back to when posterUrl is null.
 *
 * Cursor-paginated via Paging 3 (CatalogRepository.pagedItems) - keeps only
 * a bounded window of loaded pages in memory rather than the whole ~31k-row
 * catalog, and pages in automatically as the list scrolls near its end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    contentPadding: PaddingValues,
    catalogRepository: CatalogRepository?,
    embedder: OnDeviceEmbedder?,
    facetDecoder: FacetDecoder = remember { RuleBasedFacetDecoder() },
    onAdd: (CatalogItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf<MediaType?>(null) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var selectedSort by remember { mutableStateOf(CatalogSort.MOST_VOTED) }
    var filterSheetOpen by remember { mutableStateOf(false) }

    var queryInput by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(queryInput) {
        delay(300)
        debouncedQuery = queryInput
    }

    // Semantic mode: queries >= 3 chars hit the embedding + RRF pipeline.
    // Falls back to FTS paging if the embedder is unavailable or init fails.
    var semanticResults by remember { mutableStateOf<List<CatalogItem>?>(null) }
    val semanticMode = debouncedQuery.length >= 3
    LaunchedEffect(debouncedQuery) {
        if (!semanticMode || catalogRepository == null || embedder == null) {
            semanticResults = null
            return@LaunchedEffect
        }
        scope.launch {
            val ready = embedder.init()
            semanticResults = if (ready) {
                catalogRepository.semanticSearch(
                    query = debouncedQuery,
                    context = context,
                    embedder = embedder,
                    facetDecoder = facetDecoder,
                )
            } else null
        }
    }

    val titleTypeFilter = when (selectedType) {
        MediaType.MOVIE -> "movie"
        MediaType.TV -> "tvSeries"
        null -> null
    }

    val pagingFlow = remember(debouncedQuery, titleTypeFilter, selectedGenre, selectedSort, catalogRepository) {
        if (semanticMode) emptyFlow<PagingData<CatalogItem>>()
        else catalogRepository?.pagedItems(
            query = debouncedQuery,
            titleType = titleTypeFilter,
            genre = selectedGenre,
            sort = selectedSort,
        ) ?: emptyFlow<PagingData<CatalogItem>>()
    }
    val items: LazyPagingItems<CatalogItem> = pagingFlow.collectAsLazyPagingItems()
    val activeFilterCount = listOfNotNull(selectedGenre).size

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                placeholder = { Text("Search the catalog") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (queryInput.isNotEmpty()) {
                    { IconButton(onClick = { queryInput = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )

            TypeSegmentedControl(
                selected = selectedType,
                onSelect = { selectedType = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FilterButton(count = activeFilterCount, onClick = { filterSheetOpen = true })
                Spacer(modifier = Modifier.width(8.dp))
                ActiveFilterChip(label = selectedSort.label, onRemove = null)
                if (selectedGenre != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = selectedGenre ?: "", onRemove = { selectedGenre = null })
                }
            }

            if (semanticResults != null) {
                LazyColumn {
                    items(items = semanticResults!!, key = { it.tconst }) { item ->
                        DiscoverRow(item = item, onAdd = { onAdd(item) })
                    }
                }
            } else {
                LazyColumn {
                    items(count = items.itemCount, key = items.itemKey { it.tconst }) { index ->
                        val item = items[index]
                        if (item != null) {
                            DiscoverRow(item = item, onAdd = { onAdd(item) })
                        }
                    }
                }
            }
        }

        var pendingGenre by remember(selectedGenre) { mutableStateOf(selectedGenre) }
        var pendingSort by remember(selectedSort) { mutableStateOf(selectedSort) }
        FilterSidePanel(
            visible = filterSheetOpen,
            onDismiss = { filterSheetOpen = false },
            onClear = {
                pendingGenre = null
                pendingSort = CatalogSort.MOST_VOTED
                selectedGenre = null
                selectedSort = CatalogSort.MOST_VOTED
                filterSheetOpen = false
            },
            onApply = {
                selectedGenre = pendingGenre
                selectedSort = pendingSort
                filterSheetOpen = false
            },
            tabLabels = listOf("Filters", "Sort"),
        ) { tab ->
            LazyColumn {
                if (tab == 0) {
                    items(IMDB_GENRES) { genreName ->
                        SelectableListRow(
                            label = genreName,
                            selected = pendingGenre == genreName,
                            onClick = { pendingGenre = if (pendingGenre == genreName) null else genreName },
                        )
                    }
                } else {
                    items(CatalogSort.entries) { sort ->
                        SelectableListRow(label = sort.label, selected = pendingSort == sort, onClick = { pendingSort = sort })
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverRow(item: CatalogItem, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp, 66.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.width(8.dp))
                Tag(if (item.titleType == "tvSeries") "TV" else "Movie")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                item.startYear?.let {
                    Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.averageRating?.let {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(12.dp),
                    )
                    Text(
                        "%.1f".format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Add ${item.title} to library")
        }
    }
}

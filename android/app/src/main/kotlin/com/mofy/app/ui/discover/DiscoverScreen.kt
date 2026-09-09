package com.mofy.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.mofy.app.data.catalog.CatalogItem
import com.mofy.app.data.catalog.CatalogRepository
import com.mofy.app.data.catalog.CatalogSort
import com.mofy.app.data.catalog.DiscoverSource
import com.mofy.app.data.catalog.IMDB_GENRES
import com.mofy.app.data.catalog.SyncedCatalogDao
import com.mofy.app.data.catalog.SyncedCatalogItem
import com.mofy.app.data.catalog.SyncedCatalogPagingSource
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.ActiveFilterChip
import com.mofy.app.ui.components.FilterButton
import com.mofy.app.ui.components.FilterSidePanel
import com.mofy.app.ui.components.SelectableListRow
import com.mofy.app.ui.components.Tag
import com.mofy.app.ui.components.TypeSegmentedControl
import com.mofy.app.ui.icons.AppIcons
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mofy.app.search.FacetDecoder
import com.mofy.app.search.OnDeviceEmbedder
import com.mofy.app.search.RuleBasedFacetDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
    syncedCatalogDao: SyncedCatalogDao? = null,
    onAdd: (CatalogItem) -> Unit,
    // Lets Home's "More" links open Discover pre-filtered (e.g. Upcoming TV
    // -> source=NEW_AND_UPCOMING, type=TV) instead of always landing on ALL.
    initialSource: DiscoverSource = DiscoverSource.ALL,
    initialSort: CatalogSort = CatalogSort.MOST_VOTED,
    initialType: MediaType? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf(initialType) }
    var selectedGenres by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDecades by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedRuntimeBucket by remember { mutableStateOf<com.mofy.app.data.catalog.RuntimeBucket?>(null) }
    var selectedRating by remember { mutableStateOf<com.mofy.app.data.catalog.RatingThreshold?>(null) }
    var selectedSort by remember { mutableStateOf(initialSort) }
    var discoverSource by remember { mutableStateOf(initialSource) }
    var filterSheetOpen by remember { mutableStateOf(false) }

    var queryInput by remember { mutableStateOf("") }
    // committedQuery only updates on IME Search/Done action — avoids per-keystroke embedding calls.
    var committedQuery by remember { mutableStateOf("") }
    // debouncedQuery drives the FTS paging flow (cheap, fine to run on keystrokes).
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(queryInput) {
        delay(300)
        debouncedQuery = queryInput
    }

    // Semantic mode: queries >= 3 chars hit the embedding + RRF pipeline.
    // Falls back to FTS paging if the embedder is unavailable or init fails.
    var semanticResults by remember { mutableStateOf<List<CatalogItem>?>(null) }
    val semanticMode = committedQuery.length >= 3
    LaunchedEffect(committedQuery) {
        if (!semanticMode || catalogRepository == null || embedder == null) {
            semanticResults = null
            return@LaunchedEffect
        }
        scope.launch {
            val ready = embedder.init()
            semanticResults = if (ready) {
                catalogRepository.semanticSearch(
                    query = committedQuery,
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

    // ADR 0009 task 10: the "New & Upcoming" filter swaps the paging source
    // to the synced TMDB feed tables (a separate source - CatalogPagingSource
    // pages the bundled catalog.db and structurally can't cross databases in
    // one query). Semantic search and the bundled catalog stay as-is.
    val syncedMode = discoverSource == DiscoverSource.NEW_AND_UPCOMING &&
        syncedCatalogDao != null && !semanticMode

    val pagingFlow = remember(
        debouncedQuery, titleTypeFilter, selectedGenres, selectedDecades, selectedRuntimeBucket, selectedRating,
        selectedSort, catalogRepository, discoverSource, syncedCatalogDao, semanticMode,
    ) {
        if (syncedMode || semanticMode) emptyFlow<PagingData<CatalogItem>>()
        else catalogRepository?.pagedItems(
            query = debouncedQuery,
            titleType = titleTypeFilter,
            genres = selectedGenres,
            decades = selectedDecades,
            runtimeBucket = selectedRuntimeBucket,
            minRating = selectedRating,
            sort = selectedSort,
        ) ?: emptyFlow<PagingData<CatalogItem>>()
    }
    val items: LazyPagingItems<CatalogItem> = pagingFlow.collectAsLazyPagingItems()

    // Movie/TV segmented control filters the synced feed too - UPCOMING is
    // the movie kind, AIRING_TODAY the TV kind (see SyncedCatalogRepository).
    val syncedKindFilter = when (selectedType) {
        MediaType.MOVIE -> "UPCOMING"
        MediaType.TV -> "AIRING_TODAY"
        null -> null
    }
    val syncedPagingFlow = remember(discoverSource, syncedCatalogDao, semanticMode, syncedKindFilter) {
        val dao = syncedCatalogDao
        if (dao != null && discoverSource == DiscoverSource.NEW_AND_UPCOMING && !semanticMode) {
            Pager(
                config = PagingConfig(pageSize = 40, initialLoadSize = 40, prefetchDistance = 40),
                pagingSourceFactory = { SyncedCatalogPagingSource(dao, kind = syncedKindFilter) },
            ).flow
        } else {
            emptyFlow<PagingData<SyncedCatalogItem>>()
        }
    }
    val syncedItems: LazyPagingItems<SyncedCatalogItem> = syncedPagingFlow.collectAsLazyPagingItems()
    val activeFilterCount = selectedGenres.size + selectedDecades.size +
        listOfNotNull(selectedRuntimeBucket, selectedRating).size

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                placeholder = { Text("Search the catalog") },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = if (queryInput.isNotEmpty()) {
                    { IconButton(onClick = { queryInput = ""; committedQuery = "" }) { Icon(AppIcons.Close, contentDescription = "Clear search") } }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { committedQuery = queryInput },
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )

            TypeSegmentedControl(
                selected = selectedType,
                onSelect = { selectedType = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                FilterButton(count = activeFilterCount, onClick = { filterSheetOpen = true })
                Spacer(modifier = Modifier.width(8.dp))
                ActiveFilterChip(label = selectedSort.label, onRemove = null)
                if (discoverSource == DiscoverSource.NEW_AND_UPCOMING) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = discoverSource.label, onRemove = { discoverSource = DiscoverSource.ALL })
                }
                selectedGenres.forEach { g ->
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = g, onRemove = { selectedGenres = selectedGenres - g })
                }
                selectedDecades.forEach { d ->
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = "${d}s", onRemove = { selectedDecades = selectedDecades - d })
                }
                selectedRuntimeBucket?.let { rb ->
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = rb.label, onRemove = { selectedRuntimeBucket = null })
                }
                selectedRating?.let { r ->
                    Spacer(modifier = Modifier.width(8.dp))
                    ActiveFilterChip(label = r.label, onRemove = { selectedRating = null })
                }
            }

            if (syncedMode) {
                LazyColumn {
                    items(count = syncedItems.itemCount, key = syncedItems.itemKey { it.id }) { index ->
                        val item = syncedItems[index]
                        if (item != null) {
                            SyncedDiscoverRow(item = item, onAdd = { onAdd(item.toCatalogItem()) })
                        }
                    }
                }
            } else if (semanticResults != null) {
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

        var pendingGenres by remember(selectedGenres) { mutableStateOf(selectedGenres) }
        var pendingDecades by remember(selectedDecades) { mutableStateOf(selectedDecades) }
        var pendingRuntimeBucket by remember(selectedRuntimeBucket) { mutableStateOf(selectedRuntimeBucket) }
        var pendingRating by remember(selectedRating) { mutableStateOf(selectedRating) }
        var pendingSort by remember(selectedSort) { mutableStateOf(selectedSort) }
        var pendingDiscoverSource by remember(discoverSource) { mutableStateOf(discoverSource) }
        FilterSidePanel(
            visible = filterSheetOpen,
            onDismiss = { filterSheetOpen = false },
            onClear = {
                pendingGenres = emptySet()
                pendingDecades = emptySet()
                pendingRuntimeBucket = null
                pendingRating = null
                pendingSort = CatalogSort.MOST_VOTED
                pendingDiscoverSource = DiscoverSource.ALL
                selectedGenres = emptySet()
                selectedDecades = emptySet()
                selectedRuntimeBucket = null
                selectedRating = null
                selectedSort = CatalogSort.MOST_VOTED
                discoverSource = DiscoverSource.ALL
                filterSheetOpen = false
            },
            onApply = {
                selectedGenres = pendingGenres
                selectedDecades = pendingDecades
                selectedRuntimeBucket = pendingRuntimeBucket
                selectedRating = pendingRating
                selectedSort = pendingSort
                discoverSource = pendingDiscoverSource
                filterSheetOpen = false
            },
            tabLabels = listOf("Filters", "Sort"),
        ) { tab ->
            LazyColumn {
                if (tab == 0) {
                    // Source filter (ADR 0009 task 10): synced TMDB feed
                    // tables as a distinct paging source, not a sort on the
                    // bundled catalog - lives at the top of the Filters tab,
                    // outside the expandable categories since it's a single toggle.
                    item {
                        SelectableListRow(
                            label = DiscoverSource.NEW_AND_UPCOMING.label,
                            selected = pendingDiscoverSource == DiscoverSource.NEW_AND_UPCOMING,
                            onClick = {
                                pendingDiscoverSource = if (pendingDiscoverSource == DiscoverSource.NEW_AND_UPCOMING) {
                                    DiscoverSource.ALL
                                } else {
                                    DiscoverSource.NEW_AND_UPCOMING
                                }
                            },
                        )
                    }
                    item {
                        com.mofy.app.ui.components.ExpandableFilterSection(
                            title = "Genres",
                            selectedCount = pendingGenres.size,
                        ) {
                            items(IMDB_GENRES) { genreName ->
                                SelectableListRow(
                                    label = genreName,
                                    selected = genreName in pendingGenres,
                                    onClick = {
                                        pendingGenres = if (genreName in pendingGenres) {
                                            pendingGenres - genreName
                                        } else {
                                            pendingGenres + genreName
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item {
                        com.mofy.app.ui.components.ExpandableFilterSection(
                            title = "Decades",
                            selectedCount = pendingDecades.size,
                        ) {
                            items(com.mofy.app.data.catalog.CATALOG_DECADES) { decade ->
                                SelectableListRow(
                                    label = "${decade}s",
                                    selected = decade in pendingDecades,
                                    onClick = {
                                        pendingDecades = if (decade in pendingDecades) {
                                            pendingDecades - decade
                                        } else {
                                            pendingDecades + decade
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item {
                        com.mofy.app.ui.components.ExpandableFilterSection(
                            title = "Runtime",
                            selectedCount = if (pendingRuntimeBucket != null) 1 else 0,
                        ) {
                            items(com.mofy.app.data.catalog.RuntimeBucket.entries) { bucket ->
                                SelectableListRow(
                                    label = bucket.label,
                                    selected = pendingRuntimeBucket == bucket,
                                    onClick = { pendingRuntimeBucket = if (pendingRuntimeBucket == bucket) null else bucket },
                                )
                            }
                        }
                    }
                    item {
                        com.mofy.app.ui.components.ExpandableFilterSection(
                            title = "Rating",
                            selectedCount = if (pendingRating != null) 1 else 0,
                        ) {
                            items(com.mofy.app.data.catalog.RatingThreshold.entries) { threshold ->
                                SelectableListRow(
                                    label = threshold.label,
                                    selected = pendingRating == threshold,
                                    onClick = { pendingRating = if (pendingRating == threshold) null else threshold },
                                )
                            }
                        }
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

/** Reused by SearchScreen (Discover + Library fused search) as well as Discover itself. */
@Composable
fun DiscoverRow(item: CatalogItem, onAdd: () -> Unit) {
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
            Icon(AppIcons.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        AppIcons.Star,
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
            Icon(AppIcons.Add, contentDescription = "Add ${item.title} to library")
        }
    }
}

@Composable
private fun SyncedDiscoverRow(item: SyncedCatalogItem, onAdd: () -> Unit) {
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
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(AppIcons.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.width(8.dp))
                Tag(if (item.mediaType == "tv") "TV" else "Movie")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                item.releaseDate?.let {
                    Text(it.take(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(AppIcons.Add, contentDescription = "Add ${item.title} to library")
        }
    }
}

/**
 * Maps a synced feed row onto the CatalogItem vocabulary so the existing add
 * flow (MainActivity's onAdd → resolveMatch(title, mediaType)) works
 * unchanged - "tvSeries"/"movie" titleType is the mapping MainActivity's
 * click handler already switches on.
 */
private fun SyncedCatalogItem.toCatalogItem(): CatalogItem = CatalogItem(
    tconst = "synced:$id",
    title = title,
    titleType = if (mediaType == "tv") "tvSeries" else "movie",
    // See HomeScreen's toHomeCatalogItem() - on_the_air/airing_today only
    // give us first_air_date (premiere date), not a next-episode date;
    // showing it here would be misleading for long-running shows.
    // See HomeScreen's identical mapping for why AIRING_TODAY uses
    // firstSeenEpochMillis (last-synced signal) instead of releaseDate
    // (TMDB's first_air_date - the show's original premiere, not "new").
    startYear = if (kind == "AIRING_TODAY") {
        Instant.ofEpochMilli(firstSeenEpochMillis).atZone(ZoneId.systemDefault()).year
    } else {
        releaseDate?.take(4)?.toIntOrNull()
    },
    genres = genres,
    averageRating = null,
    numVotes = null,
    overview = overview,
    runtimeMinutes = null,
    posterUrl = posterUrl,
)

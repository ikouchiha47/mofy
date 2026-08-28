package com.mofy.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.catalog.CatalogItem
import com.mofy.app.data.catalog.CatalogRepository
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.library.WatchProgressDao
import com.mofy.app.data.library.WatchProgressWithItem
import kotlinx.coroutines.flow.emptyFlow

private val HOME_GENRES = listOf("Action", "Drama", "Comedy", "Thriller", "Sci-Fi", "Horror")

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    libraryDao: LibraryDao? = null,
    watchProgressDao: WatchProgressDao? = null,
    catalogRepository: CatalogRepository? = null,
    onItemClick: (LibraryItem) -> Unit = {},
    onCatalogItemClick: (CatalogItem) -> Unit = {},
    onContinueWatching: (WatchProgressWithItem) -> Unit = {},
) {
    val libraryItems by (libraryDao?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())
    val continueWatching by (watchProgressDao?.observeInProgress() ?: emptyFlow()).collectAsState(initial = emptyList())

    var popular by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var newReleases by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var genreSections by remember { mutableStateOf<List<Pair<String, List<CatalogItem>>>>(emptyList()) }

    LaunchedEffect(catalogRepository) {
        if (catalogRepository == null) return@LaunchedEffect
        popular = catalogRepository.popularItems(6)
        newReleases = catalogRepository.newReleases(6)
        genreSections = HOME_GENRES.map { genre ->
            genre to catalogRepository.byGenre(genre, 6)
        }.filter { it.second.isNotEmpty() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
            start = 0.dp,
            end = 0.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader("Continue Watching")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(continueWatching, key = { it.libraryItemId }) { wp ->
                        ContinueWatchingCard(wp, onClick = { onContinueWatching(wp) })
                    }
                }
            }
        }

        if (libraryItems.isNotEmpty()) {
            item {
                SectionHeader("Recently Added")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(libraryItems, key = { it.id }) { item ->
                        LibraryPosterCard(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }

        if (popular.isNotEmpty()) {
            item {
                SectionHeader("All Time Classics")
                CatalogRow(popular, onCatalogItemClick)
            }
        }

        if (newReleases.isNotEmpty()) {
            item {
                SectionHeader("New Releases")
                CatalogRow(newReleases, onCatalogItemClick)
            }
        }

        items(genreSections, key = { it.first }) { (genre, items) ->
            Column {
                SectionHeader(genre)
                CatalogRow(items, onCatalogItemClick)
            }
        }

        if (libraryItems.isEmpty() && popular.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Loading catalog…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(wp: WatchProgressWithItem, onClick: () -> Unit) {
    val progress = if (wp.durationMs > 0) wp.positionMs.toFloat() / wp.durationMs else 0f
    Column(modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (wp.posterUrl != null) {
                AsyncImage(
                    model = wp.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Progress bar at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(wp.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(wp.year ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
}

@Composable
private fun CatalogRow(items: List<CatalogItem>, onClick: (CatalogItem) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(items, key = { it.tconst }) { item ->
            CatalogCard(item, onClick = { onClick(item) })
        }
    }
}

@Composable
private fun CatalogCard(item: CatalogItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(136.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.averageRating != null) {
                Text(
                    "★ ${"%.1f".format(item.averageRating)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            item.startYear?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryPosterCard(item: LibraryItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        if (item.posterUrl != null) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = "${item.title} poster",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .height(136.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(136.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            item.year ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

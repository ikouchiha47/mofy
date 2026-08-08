package com.mofy.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 */
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    libraryDao: LibraryDao? = null,
    genreRepository: GenreRepository? = null,
    onImportClick: () -> Unit,
    onItemClick: (LibraryItem) -> Unit = {},
) {
    val items by (libraryDao?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var selectedType by remember { mutableStateOf<MediaType?>(null) }
    var selectedGenreId by remember { mutableStateOf<Int?>(null) }
    var genreNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(genreRepository) {
        genreNames = genreRepository?.getAllAsMap() ?: emptyMap()
    }

    val availableGenreIds = remember(items, genreNames) {
        items.flatMap { it.resolvedGenreIds }.distinct().filter { it in genreNames }.sortedBy { genreNames[it] }
    }
    val filtered = remember(items, selectedType, selectedGenreId) {
        items.filter { item ->
            (selectedType == null || item.mediaType == selectedType?.name) &&
                (selectedGenreId == null || selectedGenreId in item.resolvedGenreIds)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedButton(
            onClick = onImportClick,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Import from device", modifier = Modifier.padding(start = 8.dp))
        }

        if (items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                item { FilterChip(label = "All", selected = selectedType == null) { selectedType = null } }
                item { FilterChip(label = "Movies", selected = selectedType == MediaType.MOVIE) { selectedType = MediaType.MOVIE } }
                item { FilterChip(label = "TV", selected = selectedType == MediaType.TV) { selectedType = MediaType.TV } }
                if (availableGenreIds.isNotEmpty()) {
                    item {
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                    }
                }
                items(availableGenreIds) { genreId ->
                    FilterChip(
                        label = genreNames[genreId] ?: return@items,
                        selected = selectedGenreId == genreId,
                        onClick = { selectedGenreId = if (selectedGenreId == genreId) null else genreId },
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

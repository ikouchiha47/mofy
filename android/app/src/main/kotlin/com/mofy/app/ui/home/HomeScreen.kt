package com.mofy.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.library.toMediaResult
import kotlinx.coroutines.flow.emptyFlow

/**
 * Home tab body. The top bar (title + join-session/search icons) lives in
 * MainActivity's Scaffold topBar slot, not here - see MainActivity.kt for why
 * (avoids double-counting the status bar inset). Continue Watching /
 * Recommended for You land with Phase 07/09 - Recently Added is real data
 * now, sourced from Confirm Match's "Save to Library" action.
 */
@Composable
fun HomeScreen(contentPadding: PaddingValues, libraryDao: LibraryDao? = null, onItemClick: (LibraryItem) -> Unit = {}) {
    val items by (libraryDao?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing in your library yet — head to Browse to find something.",
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                "Recently Added",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    LibraryPosterCard(item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun LibraryPosterCard(item: LibraryItem, onClick: () -> Unit) {
    val result: MediaResult = item.toMediaResult()
    Column(modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        if (result.posterUrl != null) {
            AsyncImage(
                model = result.posterUrl,
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
        Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(
            item.year ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

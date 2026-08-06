package com.mofy.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryDownload
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.library.toMediaResult
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun DetailScreen(
    contentPadding: PaddingValues,
    itemKey: String,
    libraryDao: LibraryDao,
    onSearchForTorrent: (LibraryItem) -> Unit,
) {
    val item by libraryDao.observeByKey(itemKey).collectAsState(initial = null)
    val downloads by (item?.let { libraryDao.observeDownloads(it.key) } ?: emptyFlow())
        .collectAsState(initial = emptyList())

    val current = item ?: return
    val result = current.toMediaResult()

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        if (result.posterUrl != null) {
            AsyncImage(
                model = result.posterUrl,
                contentDescription = "${current.title} poster",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        Text(current.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            "${current.year ?: "—"} · ${current.mediaType}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(current.overview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))

        Text(
            "Downloads",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (downloads.isEmpty()) {
            Button(onClick = { onSearchForTorrent(current) }) {
                Text("Search for torrent")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(downloads) { download -> DownloadRow(download) }
            }
            Button(onClick = { onSearchForTorrent(current) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Search for another")
            }
        }
    }
}

@Composable
private fun DownloadRow(download: LibraryDownload) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(download.uri, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

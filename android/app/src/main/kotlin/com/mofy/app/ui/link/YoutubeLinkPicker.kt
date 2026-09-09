package com.mofy.app.ui.link

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.playback.youtube.YoutubeSearchClient
import com.mofy.app.playback.youtube.YoutubeSearchResult
import com.mofy.app.playback.youtube.YoutubeStreamResolver
import com.mofy.app.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search YouTube (NewPipeExtractor, not a WebView - see YoutubeSearchClient)
 * and pick a resolution, for LinkScreen's "Link a YouTube video" option.
 * [onPicked] receives the bare video ID and the chosen resolution string
 * (one of the video's actual availableResolutions, not an assumed "best") -
 * the caller is responsible for encoding "youtube:$videoId?res=$resolution"
 * into a LibraryLink.movieUri.
 */
@Composable
fun YoutubeLinkPicker(
    contentPadding: PaddingValues,
    onPicked: (videoId: String, resolution: String, title: String, thumbnailUrl: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YoutubeSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var selected by remember { mutableStateOf<YoutubeSearchResult?>(null) }
    var resolutions by remember { mutableStateOf<List<String>>(emptyList()) }
    var resolvingResolutions by remember { mutableStateOf(false) }
    var resolutionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        val sel = selected ?: return@LaunchedEffect
        resolvingResolutions = true
        resolutionError = null
        resolutions = try {
            withContext(Dispatchers.IO) { YoutubeStreamResolver.fetch(sel.videoId).availableResolutions }
        } catch (e: Exception) {
            resolutionError = "Couldn't load resolutions: ${e.message}"
            emptyList()
        }
        resolvingResolutions = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        if (selected == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search YouTube") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            if (query.isBlank()) return@KeyboardActions
                            searching = true
                            searchError = null
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel) { Icon(AppIcons.Close, contentDescription = "Cancel") }
            }

            LaunchedEffect(searching) {
                if (!searching) return@LaunchedEffect
                results = try {
                    withContext(Dispatchers.IO) { YoutubeSearchClient.search(query) }
                } catch (e: Exception) {
                    searchError = "Search failed: ${e.message}"
                    emptyList()
                }
                searching = false
            }

            if (searching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (searchError != null) {
                Text(searchError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(results, key = { it.videoId }) { result ->
                        SearchResultRow(result, onClick = { selected = result })
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                IconButton(onClick = { selected = null; resolutions = emptyList() }) {
                    Icon(AppIcons.ArrowBackAutoMirrored, contentDescription = "Back to search")
                }
                Text(selected!!.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            when {
                resolvingResolutions -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                resolutionError != null -> Text(resolutionError!!, color = MaterialTheme.colorScheme.error)
                resolutions.isEmpty() -> Text("No resolutions found for this video.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("Pick a resolution", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn {
                        items(resolutions) { resolution ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPicked(selected!!.videoId, resolution, selected!!.title, selected!!.thumbnailUrl)
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(resolution, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: YoutubeSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp, 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (result.thumbnailUrl != null) {
                AsyncImage(
                    model = result.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatDuration(result.durationSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

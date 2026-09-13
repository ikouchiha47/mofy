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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
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
    // Set when opened from an existing library item's Link screen - its
    // title is already known, so search runs immediately instead of asking
    // the user to retype what's already on screen. Blank for the "new item"
    // entry point, where there's no title yet to seed with.
    initialQuery: String = "",
) {
    // This composable is hosted inside a plain Dialog (LinkScreen), which -
    // unlike a normal Activity window - doesn't resize/pan for the soft
    // keyboard by default in Compose. Without this, the search field never
    // visibly receives IME input (typing appeared to do nothing, confirmed
    // on a real device): the dialog's window needs SOFT_INPUT_ADJUST_RESIZE
    // explicitly, same as any Compose Dialog + text input combination needs.
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<YoutubeSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(initialQuery.isNotBlank()) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        // MainActivity owns the app's single Scaffold topBar, so a screen
        // hosted in a Dialog never sees it and has to reproduce it locally.
        // Metrics/styles mirror Material3's TopAppBar: a 64dp bar, a 48dp
        // icon button inset 4dp from the edge, the title inset 12dp after
        // it, and the titleLarge style (Bungee, all caps) every screen uses.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(AppIcons.Close, contentDescription = "Close")
            }
            Text(
                "YouTube",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 16.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    )
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

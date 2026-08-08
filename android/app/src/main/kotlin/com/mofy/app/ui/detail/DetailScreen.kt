package com.mofy.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.library.Feedback
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.data.library.LibraryDownload
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.MediaType
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    contentPadding: PaddingValues,
    itemId: String,
    libraryDao: LibraryDao,
    genreRepository: GenreRepository,
    onSearchForTorrent: (LibraryItem) -> Unit,
    onLink: (LibraryItem) -> Unit,
) {
    val context = LocalContext.current
    val item by libraryDao.observeById(itemId).collectAsState(initial = null)
    val downloads by (item?.let { libraryDao.observeDownloads(it.id) } ?: emptyFlow())
        .collectAsState(initial = emptyList())
    val activeLink by (item?.let { libraryDao.observeActiveLink(it.id) } ?: emptyFlow())
        .collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    val current = item ?: return
    var genreNames by remember(current.id) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(current.id) {
        genreNames = if (current.resolvedGenreIds.isNotEmpty()) {
            genreRepository.resolveNames(current.resolvedGenreIds)
        } else {
            current.resolvedGenreNames
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (current.posterUrl != null) {
                    AsyncImage(
                        model = current.posterUrl,
                        contentDescription = "${current.title} poster",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.headlineSmall)
                    if (current.originalTitle != null) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            if (current.romanizedOriginalTitle != null) {
                                com.mofy.app.ui.components.Tag(current.romanizedOriginalTitle)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            com.mofy.app.ui.components.Tag(current.originalTitle)
                        }
                    }
                    Text(
                        current.year ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (genreNames.isNotEmpty()) {
                        Text(
                            genreNames.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Always editable, not just shown when missing - Browse-
                    // derived types can be wrong too, see ADR 0004.
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        MediaTypeChip(
                            label = "Movie",
                            selected = current.mediaType == MediaType.MOVIE.name,
                            onClick = {
                                coroutineScope.launch {
                                    libraryDao.update(current.copy(mediaType = MediaType.MOVIE.name))
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        MediaTypeChip(
                            label = "TV",
                            selected = current.mediaType == MediaType.TV.name,
                            onClick = {
                                coroutineScope.launch {
                                    libraryDao.update(current.copy(mediaType = MediaType.TV.name))
                                }
                            },
                        )
                    }
                }
            }
            Text(current.overview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))

            val isLinked = activeLink != null
            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                FeedbackIconButton(
                    icon = Icons.Filled.ThumbUp,
                    label = "Like",
                    active = current.feedback == Feedback.LIKE,
                    onClick = {
                        val next = if (current.feedback == Feedback.LIKE) null else Feedback.LIKE
                        coroutineScope.launch { libraryDao.update(current.copy(feedback = next)) }
                    },
                )
                Spacer(modifier = Modifier.width(28.dp))
                FeedbackIconButton(
                    icon = Icons.Filled.Whatshot,
                    label = "Super Like",
                    active = current.feedback == Feedback.SUPER_LIKE,
                    onClick = {
                        val next = if (current.feedback == Feedback.SUPER_LIKE) null else Feedback.SUPER_LIKE
                        coroutineScope.launch { libraryDao.update(current.copy(feedback = next)) }
                    },
                )
                Spacer(modifier = Modifier.width(28.dp))
                FeedbackIconButton(
                    icon = Icons.Filled.ThumbDown,
                    label = "Not Interested",
                    active = current.feedback == Feedback.NOT_INTERESTED,
                    onClick = {
                        val next = if (current.feedback == Feedback.NOT_INTERESTED) null else Feedback.NOT_INTERESTED
                        coroutineScope.launch { libraryDao.update(current.copy(feedback = next)) }
                    },
                )
            }
            Button(
                onClick = {
                    val link = activeLink
                    if (link != null) {
                        val playIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(android.net.Uri.parse(link.movieUri), "video/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(playIntent) }
                            .onFailure {
                                android.widget.Toast.makeText(context, "No app can play this file", android.widget.Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                enabled = isLinked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Text("▶ Play")
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                if (isLinked) {
                    Button(
                        onClick = { onLink(current) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3ECF8E)),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Linked")
                    }
                } else {
                    OutlinedButton(
                        onClick = { onLink(current) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Link")
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = {
                        android.widget.Toast.makeText(context, "Watch Party is coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Watch Party")
                }
            }

            Text(
                "Downloads",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            if (downloads.isEmpty()) {
                Button(onClick = { onSearchForTorrent(current) }, shape = MaterialTheme.shapes.small) {
                    Text("Search")
                }
            }
        }
        items(downloads) { download -> DownloadRow(download) }
        if (downloads.isNotEmpty()) {
            item {
                Button(
                    onClick = { onSearchForTorrent(current) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Search")
                }
            }
        }
    }
}

@Composable
private fun FeedbackIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).align(androidx.compose.ui.Alignment.Center),
        )
    }
}

@Composable
private fun MediaTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

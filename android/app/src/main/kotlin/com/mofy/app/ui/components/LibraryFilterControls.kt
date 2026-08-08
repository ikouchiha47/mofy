package com.mofy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.tmdb.MediaType
import java.text.DateFormat
import java.util.Date

/**
 * Filter/list-row UI shared by LibraryScreen and the Home search screen -
 * kept in one place instead of duplicated per screen, since both browse
 * the same library data with the same Type/Genre filtering shape.
 */
@Composable
fun TypeSegmentedControl(selected: MediaType?, onSelect: (MediaType?) -> Unit, modifier: Modifier = Modifier) {
    // Extra segments (e.g. Music) just add another SegmentOption call here later.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentOption("All", selected == null, Modifier.weight(1f)) { onSelect(null) }
        SegmentOption("Movies", selected == MediaType.MOVIE, Modifier.weight(1f)) { onSelect(MediaType.MOVIE) }
        SegmentOption("TV", selected == MediaType.TV, Modifier.weight(1f)) { onSelect(MediaType.TV) }
    }
}

@Composable
private fun SegmentOption(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FilterButton(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("Filters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        if (count > 0) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp).padding(start = 2.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove filter", modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
fun LibraryListRow(item: LibraryItem, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
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
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Tag(if (item.mediaType == MediaType.TV.name) "TV" else "Movie")
            }
            Text(
                "Date Added: ${formatDate(item.addedAtEpochMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${item.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

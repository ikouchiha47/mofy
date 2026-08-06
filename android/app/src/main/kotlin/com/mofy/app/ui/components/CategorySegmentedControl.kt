package com.mofy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mofy.app.data.tmdb.MediaType

/** Shared Movies/TV Shows toggle - used by Browse and the Import flow. */
@Composable
fun CategorySegmentedControl(selected: MediaType, onSelect: (MediaType) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SegmentOption("Movies", selected == MediaType.MOVIE, Modifier.weight(1f)) { onSelect(MediaType.MOVIE) }
        SegmentOption("TV Shows", selected == MediaType.TV, Modifier.weight(1f)) { onSelect(MediaType.TV) }
    }
}

@Composable
private fun SegmentOption(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

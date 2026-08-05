package com.mofy.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mofy.app.data.sites.SiteCatalog
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.data.tmdb.MediaType

/**
 * Browse tab. Category is a segmented control here, not a separate decision
 * screen - see docs/adrs/0003-app-navigation-and-screen-flow.md.
 */
@Composable
fun BrowseScreen(
    contentPadding: PaddingValues,
    sessionViewModel: BrowseSessionViewModel,
    onSitePicked: (TorrentSite) -> Unit,
    onEditSite: (TorrentSite?) -> Unit,
) {
    val category by sessionViewModel.selectedCategory.collectAsState()
    val currentCategory = category ?: MediaType.MOVIE

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        CategorySegmentedControl(
            selected = currentCategory,
            onSelect = { sessionViewModel.selectCategory(it) },
        )

        val sites = SiteCatalog.byCategory(currentCategory)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sites) { site ->
                SiteRow(
                    site = site,
                    onClick = { onSitePicked(site) },
                    onEditClick = { onEditSite(site) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Add a site…") },
                    supportingContent = { Text("opens the same edit form") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.clickable { onEditSite(null) },
                )
            }
        }
    }
}

@Composable
private fun CategorySegmentedControl(selected: MediaType, onSelect: (MediaType) -> Unit) {
    Row(
        modifier = Modifier
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

@Composable
private fun SiteRow(site: TorrentSite, onClick: () -> Unit, onEditClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(site.name) },
        supportingContent = { Text(site.baseUrl) },
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit ${site.name}",
                    modifier = Modifier.clickable(onClick = onEditClick),
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        },
    )
}

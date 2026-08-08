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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.CategorySegmentedControl
import kotlinx.coroutines.flow.emptyFlow

/**
 * Browse tab. Category is a segmented control here, not a separate decision
 * screen - see docs/adrs/0003-app-navigation-and-screen-flow.md.
 */
@Composable
fun BrowseScreen(
    contentPadding: PaddingValues,
    sessionViewModel: BrowseSessionViewModel,
    siteRepository: SiteRepository? = null,
    onSitePicked: (TorrentSite) -> Unit,
    onEditSite: (TorrentSite?) -> Unit,
) {
    val allSites by (siteRepository?.observeAll() ?: emptyFlow()).collectAsState(initial = emptyList())
    val category by sessionViewModel.selectedCategory.collectAsState()
    val searchContext by sessionViewModel.searchContext.collectAsState()
    val selectedSearchTerm by sessionViewModel.selectedSearchTerm.collectAsState()
    // A search context (from a detail page's "search for torrent") pins the
    // category to what that item actually is - no point letting it drift.
    val currentCategory = searchContext?.mediaType ?: category ?: MediaType.MOVIE

    // The "Movies" default is only a local display fallback above - without
    // this, sessionViewModel.selectedCategory stays null forever if the user
    // never explicitly taps a segment (since Movies already looks selected),
    // which broke the Confirm Match gate downstream. Persist the default.
    LaunchedEffect(Unit) {
        if (sessionViewModel.selectedCategory.value == null) {
            sessionViewModel.selectCategory(MediaType.MOVIE)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        searchContext?.let { context ->
            SearchContextBanner(
                context = context,
                selectedTerm = selectedSearchTerm ?: context.title,
                onSelectTerm = { sessionViewModel.selectSearchTerm(it) },
                onClear = { sessionViewModel.clearSearchContext() },
            )
        }

        CategorySegmentedControl(
            selected = currentCategory,
            onSelect = { sessionViewModel.selectCategory(it) },
        )

        val sites = allSites.filter { it.category == currentCategory }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sites) { site ->
                SiteRow(
                    site = site,
                    onClick = {
                        // Resets webViewState - without this, switching sites
                        // would restore() the *previous* site's saved WebView
                        // history/state into the new site's WebView instance.
                        sessionViewModel.selectSite(site)
                        val term = selectedSearchTerm ?: searchContext?.title
                        val loadUrl = term?.let { site.searchUrl(it) }
                        sessionViewModel.consumeSearchContext(loadUrl)
                        onSitePicked(site)
                    },
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
private fun SearchContextBanner(
    context: BrowseSessionViewModel.SearchContext,
    selectedTerm: String,
    onSelectTerm: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Searching for:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        }
        if (context.alternateTitle != null) {
            // Neither title nor alternateTitle is reliably what a torrent
            // site indexes a foreign-language release under - let the user
            // pick rather than silently guessing one, see ADR discussion.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                SearchTermChip(label = context.title, selected = selectedTerm == context.title, onClick = { onSelectTerm(context.title) })
                SearchTermChip(label = context.alternateTitle, selected = selectedTerm == context.alternateTitle, onClick = { onSelectTerm(context.alternateTitle) })
            }
        } else {
            Text(context.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SearchTermChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

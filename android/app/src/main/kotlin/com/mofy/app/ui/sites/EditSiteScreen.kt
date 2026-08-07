package com.mofy.app.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mofy.app.data.sites.HttpMethod
import com.mofy.app.data.sites.SiteSearchConfig
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.ui.components.CategorySegmentedControl

/**
 * One form for both "edit existing" and "add new" - existing just arrives
 * pre-filled, see PushedRoute.EDIT_SITE / EDIT_SITE_NEW in MainActivity.
 * Headers are edited as raw "Header: value" lines rather than a dynamic
 * key/value row list - simpler to build and matches how they're stored
 * (TorrentSiteEntity.headersRaw), and pasting from a curl command's -H flags
 * is nearly this format already.
 */
@Composable
fun EditSiteScreen(
    contentPadding: PaddingValues,
    existing: TorrentSite?,
    onSave: (TorrentSite) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: MediaType.MOVIE) }
    var titleSelector by remember { mutableStateOf(existing?.titleSelector ?: "") }
    var method by remember { mutableStateOf(existing?.searchConfig?.method ?: HttpMethod.GET) }
    var searchPath by remember { mutableStateOf(existing?.searchConfig?.searchPath ?: "") }
    var headersText by remember {
        mutableStateOf(existing?.searchConfig?.headers?.entries?.joinToString("\n") { (k, v) -> "$k: $v" } ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Site name") },
            enabled = existing == null, // name is the primary key - don't let it change under an existing row
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Text("Category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        CategorySegmentedControl(selected = category, onSelect = { category = it })
        OutlinedTextField(
            value = titleSelector,
            onValueChange = { titleSelector = it },
            label = { Text("Title CSS selector (optional)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        Text("Search", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HttpMethod.entries.forEach { candidate ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 16.dp),
                ) {
                    RadioButton(selected = method == candidate, onClick = { method = candidate })
                    Text(candidate.name)
                }
            }
        }
        if (method == HttpMethod.POST) {
            Text(
                "POST search isn't executed yet - WebView can't send custom headers " +
                    "with a POST request. Saved for later; GET works today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedTextField(
            value = searchPath,
            onValueChange = { searchPath = it },
            label = { Text("Search path or URL (use {query})") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = headersText,
            onValueChange = { headersText = it },
            label = { Text("Headers, one \"Header: value\" per line (optional)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            if (onDelete != null) {
                OutlinedButton(onClick = onDelete, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
            }
            Button(
                onClick = {
                    val headers = headersText.lineSequence()
                        .mapNotNull { line ->
                            val idx = line.indexOf(':')
                            if (idx <= 0) return@mapNotNull null
                            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }
                        .toMap()
                    onSave(
                        TorrentSite(
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            category = category,
                            titleSelector = titleSelector.trim().ifBlank { null },
                            searchConfig = searchPath.trim().takeIf { it.isNotEmpty() }?.let {
                                SiteSearchConfig(method = method, searchPath = it, headers = headers)
                            },
                        ),
                    )
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }
    }
}

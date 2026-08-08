package com.mofy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Side-opening filter/sort panel - full screen height, vertical lists
 * inside, not a bottom sheet with wrapping chip rows (that pattern had
 * real layout problems: genre chips wrapping into overlapping/cramped
 * rows at 26 IMDb genres wide). Shared across Library/Search/Discover
 * rather than reimplemented per screen - a `tabLabels` of size 1 just
 * skips rendering the TabRow.
 */
@Composable
fun FilterSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
    tabLabels: List<String> = listOf("Filters"),
    content: @Composable (selectedTab: Int) -> Unit,
) {
    if (!visible) return
    var selectedTab by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(300.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (tabLabels.size > 1) {
                    TabRow(selectedTabIndex = selectedTab) {
                        tabLabels.forEachIndexed { index, label ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    content(selectedTab)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    OutlinedButton(onClick = onClear, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("Clear") }
                    Button(onClick = onApply, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("Apply") }
                }
            }
        }
    }
}

/** One row in a FilterSidePanel tab's vertical list - a checkbox always visible, checked/unchecked rather than appearing only once selected. */
@Composable
fun SelectableListRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Checkbox(checked = selected, onCheckedChange = { onClick() }) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

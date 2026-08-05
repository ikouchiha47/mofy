package com.mofy.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Home tab body. The top bar (title + join-session/search icons) lives in
 * MainActivity's Scaffold topBar slot, not here - see MainActivity.kt for why
 * (avoids double-counting the status bar inset). Rows (Continue Watching,
 * Recommended for You, Recently Added) land with Phase 06/08 - this is the
 * shell only, no fake data.
 */
@Composable
fun HomeScreen(contentPadding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Nothing in your library yet — head to Browse to find something.",
            textAlign = TextAlign.Center,
        )
    }
}

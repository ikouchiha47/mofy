package com.mofy.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stand-in body for a pushed screen not built yet, so nav wiring stays honest
 * instead of a silent no-op. The title/back-button top bar lives in
 * MainActivity's Scaffold topBar slot, not here - see MainActivity.kt.
 */
@Composable
fun PlaceholderScreen(contentPadding: PaddingValues, note: String) {
    Box(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(note)
    }
}

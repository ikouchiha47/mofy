package com.mofy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Small non-interactive label chip - alternate names, media type badges, etc. */
@Composable
fun Tag(label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

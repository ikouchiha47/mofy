package com.mofy.app.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mofy.app.ui.icons.AppIcons
import com.mofy.app.ui.theme.MofyGood
import com.mofy.app.ui.theme.MofySurface
import com.mofy.app.ui.theme.MofyTheme
import com.mofy.app.watchtogether.SessionState

/**
 * Persistent bottom bar shown on tabs other than the player while a Watch
 * Together session stays active in the background - mockup frame 5b.
 * Tapping the bar itself returns to the session; the trailing Close icon
 * ends it outright, without first navigating back into the player.
 */
@Composable
fun LiveSessionBar(
    session: SessionState?,
    onReturnToSession: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session == null) return
    val info = session.toLiveSessionInfo()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MofySurface.copy(alpha = 0.96f))
            .border(1.dp, MofyGood.copy(alpha = 0.4f), MaterialTheme.shapes.large)
            .clickable(onClick = onReturnToSession)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MofyGood.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.labelSmall,
                color = MofyGood,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = info.watchingWithLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "tap to return · ${info.statusLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, contentDescription = "End session", tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun LiveSessionBarPreview() {
    MofyTheme {
        LiveSessionBar(
            session = previewSessionState(),
            onReturnToSession = {},
            onStop = {},
            modifier = Modifier.padding(10.dp),
        )
    }
}

package com.mofy.app.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mofy.app.ui.theme.MofyGood
import com.mofy.app.ui.theme.MofyTextDim
import com.mofy.app.ui.theme.MofyTheme
import com.mofy.app.watchtogether.SessionState

/** Detail-screen pill for an active Watch Together session - mockup frame 5a. */
@Composable
fun SessionPill(
    session: SessionState?,
    onReturnToSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session == null) return
    val info = session.toLiveSessionInfo()

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MofyGood.copy(alpha = 0.10f))
            .border(1.dp, MofyGood.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .clickable(onClick = onReturnToSession)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MofyGood),
        )
        Text(
            text = info.watchingWithLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = info.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MofyGood,
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.labelLarge,
            color = MofyTextDim,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun SessionPillPreview() {
    MofyTheme {
        SessionPill(
            session = previewSessionState(),
            onReturnToSession = {},
            modifier = Modifier.padding(14.dp),
        )
    }
}

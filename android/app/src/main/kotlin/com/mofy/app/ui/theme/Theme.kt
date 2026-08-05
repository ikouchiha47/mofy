package com.mofy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Mofy is a dark-only, cinematic UI by design (see design/browse-flow-mockup.html
 * and ADR 0003) - not an adaptive light/dark theme. Forced dark on purpose.
 */
private val MofyColorScheme = darkColorScheme(
    background = MofyBg,
    onBackground = MofyText,
    surface = MofySurface,
    onSurface = MofyText,
    surfaceVariant = MofySurfaceVariant,
    onSurfaceVariant = MofyTextDim,
    surfaceContainerHighest = MofySurfaceContainer,
    primary = MofyAccent,
    onPrimary = Color.White,
    secondary = MofyAccentBlue,
    onSecondary = Color.White,
    tertiary = MofyGood,
    onTertiary = Color.Black,
    outline = MofyBorder,
    outlineVariant = MofyBorder,
)

@Composable
fun MofyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MofyColorScheme,
        content = content,
    )
}

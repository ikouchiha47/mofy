package com.mofy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Matches design/browse-flow-mockup.html: corners are slightly rounded
 * (8-16dp depending on element size), never pill/stadium-shaped. Material3's
 * default Shapes.extraLarge (used by Button) is a full stadium radius, which
 * is why buttons rendered as pills before this was wired in - see CLAUDE.md
 * "Design guide".
 */
val MofyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

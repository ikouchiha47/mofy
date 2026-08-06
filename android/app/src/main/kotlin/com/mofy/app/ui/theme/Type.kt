package com.mofy.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mofy.app.R

// Bungee: condensed, all-caps, poster/street-style - titles and headlines only.
val BungeeFamily = FontFamily(Font(R.font.bungee_regular, FontWeight.Normal))

// Manrope: everything else - body text, labels, buttons.
val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val MofyTypography = Typography(
    displayLarge = TextStyle(fontFamily = BungeeFamily, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = BungeeFamily, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = BungeeFamily, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = BungeeFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = BungeeFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

package com.woodruff.tvexplorer.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.woodruff.tvexplorer.R

/**
 * Inter, the closest open-licensed match to Apple's San Francisco (SF Pro itself may only ship on
 * Apple platforms). "Inter Display" is the optical size for big headings, like SF Pro Display.
 * Tracking tightens as size grows, as Apple's type scale does.
 */
private val Text = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
private val Display = FontFamily(
    Font(R.font.interdisplay_semibold, FontWeight.SemiBold),
    Font(R.font.interdisplay_bold, FontWeight.Bold),
)

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

val AppTypography = Typography(
    displayLarge = style(Display, FontWeight.Bold, 52, 58, -0.022),
    displayMedium = style(Display, FontWeight.Bold, 44, 50, -0.02),
    displaySmall = style(Display, FontWeight.Bold, 36, 42, -0.018),
    headlineLarge = style(Display, FontWeight.Bold, 30, 36, -0.016),
    headlineMedium = style(Display, FontWeight.SemiBold, 26, 32, -0.014),
    headlineSmall = style(Display, FontWeight.SemiBold, 22, 28, -0.012),
    titleLarge = style(Text, FontWeight.SemiBold, 19, 25, -0.01),
    titleMedium = style(Text, FontWeight.SemiBold, 16, 22, -0.006),
    titleSmall = style(Text, FontWeight.Medium, 14, 20, -0.004),
    bodyLarge = style(Text, FontWeight.Normal, 16, 23, -0.006),
    bodyMedium = style(Text, FontWeight.Normal, 14, 20, -0.004),
    bodySmall = style(Text, FontWeight.Normal, 12, 17, 0.0),
    labelLarge = style(Text, FontWeight.SemiBold, 14, 18, 0.0),
    labelMedium = style(Text, FontWeight.Medium, 12, 16, 0.01),
    labelSmall = style(Text, FontWeight.SemiBold, 11, 14, 0.04),
)

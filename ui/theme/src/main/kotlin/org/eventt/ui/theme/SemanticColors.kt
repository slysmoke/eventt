package org.eventt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Profit/loss/warning colors used throughout the app (order prices, wallet P&L, contest
// status, ...). A single fixed hex can't read well on both a near-black and a cream
// background, so pick per-theme: pastel/bright on dark surfaces, saturated/dark on light ones.
private val PositiveDark = Color(0xFF69DB7C)
private val PositiveLight = Color(0xFF2B8A3E)
private val NegativeDark = Color(0xFFFF6B6B)
private val NegativeLight = Color(0xFFC92A2A)
private val WarningDark = Color(0xFFFF9800)
private val WarningLight = Color(0xFFB35C00)

private val isLightSurface: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() > 0.5f

val positiveColor: Color
    @Composable get() = if (isLightSurface) PositiveLight else PositiveDark

val negativeColor: Color
    @Composable get() = if (isLightSurface) NegativeLight else NegativeDark

val warningColor: Color
    @Composable get() = if (isLightSurface) WarningLight else WarningDark

package org.eve.trader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class EveColors(
    val borderColor: Color,
    val headerColor: Color,
    val accentColor: Color,
    val tradeBuyColor: Color,
    val tradeSellColor: Color
)

val DarkEveColors = EveColors(
    borderColor = DarkBorder,
    headerColor = DarkSurfaceVariant,
    accentColor = EveCyan,
    tradeBuyColor = Color(0xFF00CC66),
    tradeSellColor = Color(0xFFFF4444)
)

val LightEveColors = EveColors(
    borderColor = LightBorder,
    headerColor = LightSurfaceVariant,
    accentColor = EveBlue,
    tradeBuyColor = Color(0xFF009944),
    tradeSellColor = Color(0xFFCC0000)
)

val DarkColorScheme = darkColorScheme(
    primary = EveCyan,
    secondary = EveBlue,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

val LightColorScheme = lightColorScheme(
    primary = EveBlue,
    secondary = EveDarkBlue,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

package org.eventt.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val DarkColorScheme =
    darkColorScheme(
        primary = EveBlue,
        secondary = EveAccent,
        tertiary = EveOrange,
        background = EveDarkBg,
        surface = EveDarkSurface,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
    )

val LightColorScheme =
    lightColorScheme(
        primary = EveBlue,
        secondary = EveAccent,
        tertiary = EveOrange,
        background = Color(0xFFF5F5F5),
        surface = Color.White,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = Color.Black,
        onSurface = Color.Black,
    )

data class EveColors(
    val accentColor: Color,
    val headerColor: Color,
    val surfaceColor: Color,
)

val DarkEveColors =
    EveColors(
        accentColor = EveBlue,
        headerColor = EveDarkHeader,
        surfaceColor = EveDarkSurface,
    )

val LightEveColors =
    EveColors(
        accentColor = EveBlue,
        headerColor = Color(0xFFE8EAF6),
        surfaceColor = Color.White,
    )

package org.eventt.ui.theme

import androidx.compose.material3.ColorScheme
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

val FusionColorScheme =
    darkColorScheme(
        primary = FusionHighlight,
        secondary = FusionHighlight,
        tertiary = FusionHighlight,
        background = FusionWindow,
        surface = FusionWindow,
        surfaceVariant = FusionBase,
        error = FusionBrightText,
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onTertiary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White,
    )

val FusionEveColors =
    EveColors(
        accentColor = FusionHighlight,
        headerColor = FusionBase,
        surfaceColor = FusionWindow,
    )

val GruvboxDarkColorScheme =
    darkColorScheme(
        primary = GruvboxDarkBlue,
        secondary = GruvboxDarkPurple,
        tertiary = GruvboxDarkOrange,
        background = GruvboxDarkBg0,
        surface = GruvboxDarkBg1,
        surfaceVariant = GruvboxDarkBg2,
        error = GruvboxDarkRed,
        onPrimary = GruvboxDarkBg0,
        onSecondary = GruvboxDarkBg0,
        onTertiary = GruvboxDarkBg0,
        onBackground = GruvboxDarkFg1,
        onSurface = GruvboxDarkFg1,
        onSurfaceVariant = GruvboxDarkFg1,
    )

val GruvboxDarkEveColors =
    EveColors(
        accentColor = GruvboxDarkBlue,
        headerColor = GruvboxDarkBg2,
        surfaceColor = GruvboxDarkBg1,
    )

val GruvboxLightColorScheme =
    lightColorScheme(
        primary = GruvboxLightBlue,
        secondary = GruvboxLightPurple,
        tertiary = GruvboxLightOrange,
        background = GruvboxLightBg0,
        surface = GruvboxLightBg1,
        surfaceVariant = GruvboxLightBg2,
        error = GruvboxLightRed,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = GruvboxLightFg1,
        onSurface = GruvboxLightFg1,
        onSurfaceVariant = GruvboxLightFg1,
    )

val GruvboxLightEveColors =
    EveColors(
        accentColor = GruvboxLightBlue,
        headerColor = GruvboxLightBg2,
        surfaceColor = GruvboxLightBg1,
    )

val SrceryColorScheme =
    darkColorScheme(
        primary = SrceryOrange,
        secondary = SrceryMagenta,
        tertiary = SrceryCyan,
        background = SrceryBlack,
        surface = SrceryXgray1,
        surfaceVariant = SrceryXgray2,
        error = SrceryRed,
        onPrimary = SrceryBlack,
        onSecondary = SrceryBrightWhite,
        onTertiary = SrceryBlack,
        onBackground = SrceryBrightWhite,
        onSurface = SrceryBrightWhite,
        onSurfaceVariant = SrceryBrightWhite,
    )

val SrceryEveColors =
    EveColors(
        accentColor = SrceryOrange,
        headerColor = SrceryXgray2,
        surfaceColor = SrceryXgray1,
    )

val SolarizedLightColorScheme =
    lightColorScheme(
        primary = SolarizedBlue,
        secondary = SolarizedViolet,
        tertiary = SolarizedCyan,
        background = SolarizedBase3,
        surface = SolarizedBase2,
        surfaceVariant = SolarizedBase2,
        error = SolarizedRed,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = SolarizedBase01,
        onSurface = SolarizedBase01,
        onSurfaceVariant = SolarizedBase00,
        outline = SolarizedBase1,
    )

val SolarizedLightEveColors =
    EveColors(
        accentColor = SolarizedBlue,
        headerColor = SolarizedBase2,
        surfaceColor = SolarizedBase2,
    )

enum class ThemeVariant(
    val label: String,
    val colorScheme: ColorScheme,
    val eveColors: EveColors,
) {
    EVE_DARK("EVE Dark", DarkColorScheme, DarkEveColors),
    EVE_LIGHT("EVE Light", LightColorScheme, LightEveColors),
    FUSION("Fusion Dark", FusionColorScheme, FusionEveColors),
    GRUVBOX_DARK("Gruvbox Dark", GruvboxDarkColorScheme, GruvboxDarkEveColors),
    GRUVBOX_LIGHT("Gruvbox Light", GruvboxLightColorScheme, GruvboxLightEveColors),
    SRCERY("Srcery", SrceryColorScheme, SrceryEveColors),
    SOLARIZED_LIGHT("Solarized Light", SolarizedLightColorScheme, SolarizedLightEveColors),
}

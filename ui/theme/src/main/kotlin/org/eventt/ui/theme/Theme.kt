package org.eventt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

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

    // colorScheme/eveColors here are inert placeholders — never read once CUSTOM is actually
    // selected, since the app resolves the real colors from CustomThemeColors at that point.
    CUSTOM("Custom", DarkColorScheme, DarkEveColors),
}

// User-editable palette: the handful of colors every built-in theme above is ultimately built
// from (a Material3 ColorScheme's primary/secondary/tertiary/background/surface, plus
// EveColors.headerColor, the one field ColorScheme has no equivalent for).
data class CustomThemeColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val headerColor: Color,
) {
    companion object {
        val DEFAULT =
            CustomThemeColors(
                primary = EveBlue,
                secondary = EveAccent,
                tertiary = EveOrange,
                background = EveDarkBg,
                surface = EveDarkSurface,
                headerColor = EveDarkHeader,
            )
    }
}

// White or black text reads legibly on any background/surface color a user picks — sidesteps
// asking them to separately pick 5 more "on*" text colors just to keep things readable.
private fun autoOn(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White

fun CustomThemeColors.toColorScheme(): ColorScheme =
    if (background.luminance() > 0.5f) {
        lightColorScheme(
            primary = primary,
            onPrimary = autoOn(primary),
            secondary = secondary,
            onSecondary = autoOn(secondary),
            tertiary = tertiary,
            onTertiary = autoOn(tertiary),
            background = background,
            onBackground = autoOn(background),
            surface = surface,
            onSurface = autoOn(surface),
        )
    } else {
        darkColorScheme(
            primary = primary,
            onPrimary = autoOn(primary),
            secondary = secondary,
            onSecondary = autoOn(secondary),
            tertiary = tertiary,
            onTertiary = autoOn(tertiary),
            background = background,
            onBackground = autoOn(background),
            surface = surface,
            onSurface = autoOn(surface),
        )
    }

fun CustomThemeColors.toEveColors(): EveColors =
    EveColors(
        accentColor = primary,
        headerColor = headerColor,
        surfaceColor = surface,
    )

// Plain "RRGGBB,RRGGBB,..." (6 values, no alpha — these are opaque UI colors), matching the
// simple string key-value settings store; falls back to DEFAULT on anything unparseable rather
// than crashing on a corrupted or hand-edited setting.
fun CustomThemeColors.encode(): String =
    listOf(primary, secondary, tertiary, background, surface, headerColor)
        .joinToString(",") { "%06X".format(it.toArgb() and 0xFFFFFF) }

fun decodeCustomThemeColors(raw: String?): CustomThemeColors {
    val parts = raw?.split(",")?.mapNotNull { it.toIntOrNull(16) }
    if (parts?.size != 6) return CustomThemeColors.DEFAULT
    val colors = parts.map { Color(0xFF000000 or it.toLong()) }
    return CustomThemeColors(
        primary = colors[0],
        secondary = colors[1],
        tertiary = colors[2],
        background = colors[3],
        surface = colors[4],
        headerColor = colors[5],
    )
}

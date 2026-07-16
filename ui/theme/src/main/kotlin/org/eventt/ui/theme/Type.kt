package org.eventt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

val EveTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            ),
    )

private fun Typography.withFontFamily(family: FontFamily): Typography =
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
    )

val SystemSerifTypography = EveTypography.withFontFamily(FontFamily.Serif)
val SystemMonoTypography = EveTypography.withFontFamily(FontFamily.Monospace)

// All three below are SIL OFL 1.1, bundled under resources/font/<name>/. Only Regular/Bold are
// shipped; intermediate weights (Medium, SemiBold) fall back to whichever of those two is closer.

// Designed by Sorkin Type specifically for comfortable on-screen reading, unlike whatever
// generic serif the OS happens to have installed.
private val MerriweatherFontFamily =
    FontFamily(
        Font("font/merriweather/Merriweather-Regular.ttf", FontWeight.Normal),
        Font("font/merriweather/Merriweather-Bold.ttf", FontWeight.Bold),
    )
val MerriweatherTypography = EveTypography.withFontFamily(MerriweatherFontFamily)

// Clean, humanist grotesque built for UI — a more deliberate alternative to whatever sans the
// host OS ships.
private val WorkSansFontFamily =
    FontFamily(
        Font("font/worksans/WorkSans-Regular.ttf", FontWeight.Normal),
        Font("font/worksans/WorkSans-Bold.ttf", FontWeight.Bold),
    )
val WorkSansTypography = EveTypography.withFontFamily(WorkSansFontFamily)

// Tabular figures and high digit legibility — this app is mostly ISK numbers and price columns,
// so a coding monospace earns its place as a font choice, not just a theme accessory.
private val JetBrainsMonoFontFamily =
    FontFamily(
        Font("font/jetbrainsmono/JetBrainsMono-Regular.ttf", FontWeight.Normal),
        Font("font/jetbrainsmono/JetBrainsMono-Bold.ttf", FontWeight.Bold),
    )
val JetBrainsMonoTypography = EveTypography.withFontFamily(JetBrainsMonoFontFamily)

enum class FontChoice(
    val label: String,
    val typography: Typography,
) {
    SYSTEM_SANS("System Sans", EveTypography),
    SYSTEM_SERIF("System Serif", SystemSerifTypography),
    SYSTEM_MONO("System Mono", SystemMonoTypography),
    MERRIWEATHER("Merriweather", MerriweatherTypography),
    WORK_SANS("Work Sans", WorkSansTypography),
    JETBRAINS_MONO("JetBrains Mono", JetBrainsMonoTypography),
}

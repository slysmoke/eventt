package org.eventt.ui.theme

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CustomThemeColorsTest {
    @Test
    fun `encode then decode round-trips every color`() {
        val colors =
            CustomThemeColors(
                primary = Color(0xFF4A90D9),
                secondary = Color(0xFF533483),
                tertiary = Color(0xFFFF8C00),
                background = Color(0xFF1A1A2E),
                surface = Color(0xFF16213E),
                headerColor = Color(0xFF0F3460),
            )

        decodeCustomThemeColors(colors.encode()) shouldBe colors
    }

    @Test
    fun `null input falls back to DEFAULT`() {
        decodeCustomThemeColors(null) shouldBe CustomThemeColors.DEFAULT
    }

    @Test
    fun `malformed input falls back to DEFAULT instead of crashing`() {
        decodeCustomThemeColors("not,a,valid,color,string,at,all").shouldBe(CustomThemeColors.DEFAULT)
        decodeCustomThemeColors("4A90D9,533483").shouldBe(CustomThemeColors.DEFAULT)
    }
}

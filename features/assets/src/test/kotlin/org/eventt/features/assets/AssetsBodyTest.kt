package org.eventt.features.assets

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

// Renders the assets body headless from synthetic lines, clicks a location to filter, and writes
// PNGs to build/assets-screens/ for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class AssetsBodyTest {
    @Test
    fun `body renders and a location click filters the table`() {
        val jita = Place("st:$JITA_44", "Jita IV - Moon 4 - Caldari Navy Assembly Plant", "The Forge", 10000002, JITA_44)
        val amarr = Place("st:60008494", "Amarr VIII (Oris) - Emperor Family Academy", "Domain", 10000043, 60008494L)
        val lines =
            (0 until 24).map { i ->
                AssetLine(
                    typeId = 34 + i,
                    name = "Item ${34 + i}",
                    group = listOf("Mineral", "Salvaged Materials", "Frigate", "Moon Materials")[i % 4],
                    category = listOf("Material", "Material", "Ship", "Material")[i % 4],
                    place = if (i % 3 == 0) amarr else jita,
                    placement = Placement.entries[i % Placement.entries.size],
                    owner = listOf("Sasha Winston", "Li Slysmoke", "Corp")[i % 3],
                    isBpc = i == 5,
                    qty = 1000L * (i + 1),
                    unitPrice = if (i == 7) 0.0 else 1_000.0 * (24 - i),
                    m3 = 10.0 * (i + 1),
                )
            }
        runDesktopComposeUiTest(1440, 860) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { AssetsBody(lines, showOwner = true) {} } } }
            waitForIdle()
            val out = File("build/assets-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "all.png"))
            onNodeWithText("Domain", substring = true, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Amarr VIII (Oris) - Emperor Family Academy  ✕", useUnmergedTree = true).assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "amarr.png"))
        }
    }
}

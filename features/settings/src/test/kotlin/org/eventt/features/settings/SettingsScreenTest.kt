package org.eventt.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.eventt.core.database.DatabaseManager
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

// Renders every settings section headless on an empty database and writes one PNG per section to
// build/settings-screens/ for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @Test
    fun `every section renders`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1280, 760) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { SettingsScreen() } } }
            val out = File("build/settings-screens").apply { mkdirs() }
            listOf("General", "Trading", "Hotkeys & overlay", "P2P market", "Data sources").forEachIndexed { i, name ->
                onNodeWithText(name).performClick()
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$i.png"))
            }
        }
    }
}

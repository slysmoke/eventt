package org.eventt.core.marketlogs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MarketLogCleanerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `cleanOnStartup removes only top-level txt files, leaving other files and subdirectories untouched`() {
        File(tempDir, "My Orders-2026.07.09 0311.txt").writeText("a")
        File(tempDir, "The Forge-PLEX-2026.07.09 031048.txt").writeText("b")
        File(tempDir, "readme.md").writeText("c")
        val nested = File(tempDir, "subdir").apply { mkdirs() }
        File(nested, "nested.txt").writeText("d")

        val removed = MarketLogCleaner.cleanOnStartup(tempDir)

        removed shouldBe 2
        File(tempDir, "My Orders-2026.07.09 0311.txt").exists() shouldBe false
        File(tempDir, "The Forge-PLEX-2026.07.09 031048.txt").exists() shouldBe false
        File(tempDir, "readme.md").exists() shouldBe true
        File(nested, "nested.txt").exists() shouldBe true
    }

    @Test
    fun `cleanOnStartup returns 0 when the directory doesn't exist`() {
        val missing = File(tempDir, "does-not-exist")
        MarketLogCleaner.cleanOnStartup(missing) shouldBe 0
    }

    @Test
    fun `cleanOnStartup returns 0 when no directory is configured`() {
        MarketLogCleaner.cleanOnStartup(null) shouldBe 0
    }
}

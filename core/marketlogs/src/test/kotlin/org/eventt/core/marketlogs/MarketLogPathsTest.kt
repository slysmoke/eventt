package org.eventt.core.marketlogs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MarketLogPathsTest {
    @Test
    fun `candidatePaths on Windows uses the Documents suffix with backslash separators`() {
        val result = MarketLogPaths.candidatePaths(osName = "Windows 11", home = "C:\\Users\\bob")
        result shouldBe listOf("C:\\Users\\bob\\Documents\\EVE\\logs\\Marketlogs")
    }

    @Test
    fun `candidatePaths on Windows trims a trailing separator from home before joining`() {
        val result = MarketLogPaths.candidatePaths(osName = "Windows 11", home = "C:\\Users\\bob\\")
        result shouldBe listOf("C:\\Users\\bob\\Documents\\EVE\\logs\\Marketlogs")
    }

    @Test
    fun `candidatePaths on macOS uses the same Documents suffix as a best-effort guess`() {
        val result = MarketLogPaths.candidatePaths(osName = "Mac OS X", home = "/Users/bob")
        result shouldBe listOf("/Users/bob/Documents/EVE/logs/Marketlogs")
    }

    @Test
    fun `candidatePaths on Linux lists the Steam Proton default path first`() {
        val result = MarketLogPaths.candidatePaths(osName = "Linux", home = "/home/bob")
        result.first() shouldBe
            "/home/bob/.local/share/Steam/steamapps/compatdata/8500/pfx/drive_c/users/steamuser/Documents/EVE/logs/Marketlogs"
        (result.size > 1) shouldBe true
    }

    @Test
    fun `firstExisting returns the first candidate the predicate accepts`() {
        val candidates = listOf("/a", "/b", "/c")
        val result = MarketLogPaths.firstExisting(candidates) { it == "/b" }
        result shouldBe "/b"
    }

    @Test
    fun `firstExisting returns null when no candidate exists`() {
        val result = MarketLogPaths.firstExisting(listOf("/a", "/b")) { false }
        result shouldBe null
    }
}

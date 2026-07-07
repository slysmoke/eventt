package org.eventt.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppPathsTest {
    @TempDir
    lateinit var tempDir: File

    // ─── resolveBaseDir ─────────────────────────────────────────────────────

    @Test
    fun `resolveBaseDir uses APPDATA on Windows when set`() {
        val result =
            AppPaths.resolveBaseDir(
                osName = "Windows 11",
                home = "C:\\Users\\bob",
                appDataEnv = "C:\\Users\\bob\\AppData\\Roaming",
                xdgDataHomeEnv = null,
            )

        result shouldBe "C:\\Users\\bob\\AppData\\Roaming"
    }

    @Test
    fun `resolveBaseDir falls back to home AppData Roaming on Windows without APPDATA set`() {
        val result =
            AppPaths.resolveBaseDir(
                osName = "Windows 11",
                home = "C:\\Users\\bob",
                appDataEnv = null,
                xdgDataHomeEnv = null,
            )

        result shouldBe "C:\\Users\\bob/AppData/Roaming"
    }

    @Test
    fun `resolveBaseDir uses Library Application Support on macOS`() {
        val result =
            AppPaths.resolveBaseDir(
                osName = "Mac OS X",
                home = "/Users/bob",
                appDataEnv = null,
                xdgDataHomeEnv = null,
            )

        result shouldBe "/Users/bob/Library/Application Support"
    }

    @Test
    fun `resolveBaseDir uses XDG_DATA_HOME on Linux when set`() {
        val result =
            AppPaths.resolveBaseDir(
                osName = "Linux",
                home = "/home/bob",
                appDataEnv = null,
                xdgDataHomeEnv = "/home/bob/.custom-data",
            )

        result shouldBe "/home/bob/.custom-data"
    }

    @Test
    fun `resolveBaseDir falls back to home local share on Linux without XDG_DATA_HOME`() {
        val result =
            AppPaths.resolveBaseDir(
                osName = "Linux",
                home = "/home/bob",
                appDataEnv = null,
                xdgDataHomeEnv = null,
            )

        result shouldBe "/home/bob/.local/share"
    }

    // ─── migrateLegacyData ──────────────────────────────────────────────────

    @Test
    fun `migrateLegacyData copies files from a legacy dir into the destination`() {
        val legacy = File(tempDir, "legacy").apply { mkdirs() }
        File(legacy, "eve_trader.db").writeText("db-bytes")
        File(legacy, "token.key").writeText("key-bytes")
        val destination = File(tempDir, "new-home").apply { mkdirs() }

        AppPaths.migrateLegacyData(legacyDirs = listOf(legacy), destination = destination)

        File(destination, "eve_trader.db").readText() shouldBe "db-bytes"
        File(destination, "token.key").readText() shouldBe "key-bytes"
    }

    @Test
    fun `migrateLegacyData copies directories recursively`() {
        val legacy = File(tempDir, "legacy").apply { mkdirs() }
        File(legacy, "image-cache").apply { mkdirs() }
        File(legacy, "image-cache/icon.png").writeText("png-bytes")
        val destination = File(tempDir, "new-home").apply { mkdirs() }

        AppPaths.migrateLegacyData(legacyDirs = listOf(legacy), destination = destination)

        File(destination, "image-cache/icon.png").readText() shouldBe "png-bytes"
    }

    @Test
    fun `migrateLegacyData never overwrites a file that already exists at the destination`() {
        val legacy = File(tempDir, "legacy").apply { mkdirs() }
        File(legacy, "eve_trader.db").writeText("old-bytes")
        val destination = File(tempDir, "new-home").apply { mkdirs() }
        File(destination, "eve_trader.db").writeText("current-bytes")

        AppPaths.migrateLegacyData(legacyDirs = listOf(legacy), destination = destination)

        File(destination, "eve_trader.db").readText() shouldBe "current-bytes"
    }

    @Test
    fun `migrateLegacyData is a no-op when the legacy dir doesn't exist`() {
        val destination = File(tempDir, "new-home").apply { mkdirs() }
        val neverExisted = File(tempDir, "no-such-legacy-dir")

        AppPaths.migrateLegacyData(legacyDirs = listOf(neverExisted), destination = destination)

        destination.listFiles()?.size shouldBe 0
    }
}

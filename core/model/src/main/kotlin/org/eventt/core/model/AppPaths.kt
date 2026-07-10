package org.eventt.core.model

import java.io.File

/**
 * Per-user application data directory, following each OS's actual convention instead of always
 * dumping a dotfile straight into the home folder (that approach has no real equivalent on
 * Windows and isn't idiomatic on macOS either).
 */
object AppPaths {
    private const val APP_FOLDER = "eventt"

    // Pure function (no direct System.getenv/getProperty reads) so it's testable with
    // arbitrary OS/env combinations rather than only whatever the test JVM actually is.
    internal fun resolveBaseDir(
        osName: String,
        home: String,
        appDataEnv: String?,
        xdgDataHomeEnv: String?,
    ): String =
        when {
            osName.lowercase().contains("win") -> appDataEnv ?: "$home/AppData/Roaming"
            osName.lowercase().contains("mac") -> "$home/Library/Application Support"
            else -> xdgDataHomeEnv ?: "$home/.local/share"
        }

    // Lets a second, isolated instance of the app run alongside the real one (own DB, own Nostr
    // identity) for exercising the P2P Market as both sides of a trade — see scripts/run-p2p-test.sh.
    val appDataDir: File by lazy {
        System.getenv("EVENTT_DATA_DIR")?.let { return@lazy File(it).apply { mkdirs() } }
        val base =
            resolveBaseDir(
                osName = System.getProperty("os.name"),
                home = System.getProperty("user.home"),
                appDataEnv = System.getenv("APPDATA"),
                xdgDataHomeEnv = System.getenv("XDG_DATA_HOME"),
            )
        File(base, APP_FOLDER).apply { mkdirs() }
    }

    // Earlier versions used ~/.eve-trader (pre-rename) and ~/.eventt (post-rename, but still a
    // dotfile directly in $HOME rather than a proper per-OS app-data directory) — both checked
    // by default so an upgrade from either doesn't look like data loss. Copies rather than
    // moves, so the old files stay behind as an untouched fallback if anything goes wrong; a
    // no-op once already migrated (nothing left to find in the old locations) or on a fresh
    // install. legacyDirs/destination are parameterized so tests can point this at temp
    // directories instead of the real home folder.
    fun migrateLegacyData(
        legacyDirs: List<File> =
            listOf(
                File(System.getProperty("user.home"), ".eve-trader"),
                File(System.getProperty("user.home"), ".eventt"),
            ),
        destination: File = appDataDir,
    ) {
        legacyDirs.forEach { legacyDir ->
            if (!legacyDir.isDirectory) return@forEach
            legacyDir.listFiles()?.forEach { file ->
                val dest = File(destination, file.name)
                if (dest.exists()) return@forEach
                runCatching {
                    if (file.isDirectory) file.copyRecursively(dest) else file.copyTo(dest)
                }
            }
        }
    }
}

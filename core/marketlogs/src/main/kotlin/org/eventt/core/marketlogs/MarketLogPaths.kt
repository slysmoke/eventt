package org.eventt.core.marketlogs

import org.eventt.core.database.StaticDataDao
import java.io.File

/**
 * Locates and remembers EVE's local "Marketlogs" export folder (where the game client writes
 * order/order-book CSV exports). Auto-detection is best-effort — it depends entirely on how EVE
 * was installed (native, Steam+Proton, Wine/Lutris, ...) — so a manually configured path (set via
 * Settings) always takes priority when it still points at a real directory.
 */
object MarketLogPaths {
    private const val SETTING_KEY = "marketlogs.directory"

    // Pure — no System.getProperty/getenv reads inside — so it's testable with arbitrary
    // OS/home combinations, mirroring core/model/AppPaths.resolveBaseDir's exact shape.
    internal fun candidatePaths(
        osName: String,
        home: String,
    ): List<String> {
        val lower = osName.lowercase()
        return when {
            // Windows paths use \, not / — home arrives as "C:\Users\bob".
            lower.contains("win") -> listOf("${home.trimEnd('\\', '/')}\\Documents\\EVE\\logs\\Marketlogs")
            // Best-effort guess only — EVE's Mac client is a wrapped Windows build that
            // historically mirrors the same relative Documents/EVE/logs/Marketlogs suffix, but
            // this is unverified; the Settings UI flags it as such whenever running on macOS.
            lower.contains("mac") -> listOf("$home/Documents/EVE/logs/Marketlogs")
            // Linux has no single answer — it depends on how the Windows client is being run.
            // 8500 is EVE Online's real Steam AppID.
            else ->
                listOf(
                    "$home/.local/share/Steam/steamapps/compatdata/8500/pfx/drive_c/users/steamuser/Documents/EVE/logs/Marketlogs",
                    "$home/.steam/steam/steamapps/compatdata/8500/pfx/drive_c/users/steamuser/Documents/EVE/logs/Marketlogs",
                    "$home/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/compatdata/8500/pfx/drive_c/users/steamuser/Documents/EVE/logs/Marketlogs",
                    "$home/.wine/drive_c/users/${System.getProperty("user.name")}/Documents/EVE/logs/Marketlogs",
                )
        }
    }

    internal fun firstExisting(
        candidates: List<String>,
        isDirectory: (String) -> Boolean = { File(it).isDirectory },
    ): String? = candidates.firstOrNull(isDirectory)

    fun autoDetect(
        osName: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
    ): String? = firstExisting(candidatePaths(osName, home))

    fun getConfiguredPath(): String? = StaticDataDao.getSetting(SETTING_KEY)

    fun setConfiguredPath(path: String) = StaticDataDao.setSetting(SETTING_KEY, path)

    /** Configured value wins if it's still a real directory; otherwise falls back to a fresh auto-detect. */
    fun resolveDirectory(): File? {
        getConfiguredPath()?.let { p -> if (File(p).isDirectory) return File(p) }
        return autoDetect()?.let { File(it) }
    }
}

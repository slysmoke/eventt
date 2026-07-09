package org.eventt.core.marketlogs

import java.io.File

/** Wipes stale exports from the Marketlogs directory on app startup — nothing else lives there. */
object MarketLogCleaner {
    // Deletes every top-level *.txt file in the configured/auto-detected directory.
    // Non-recursive and extension-scoped, confined to this one directory.
    fun cleanOnStartup(dir: File? = MarketLogPaths.resolveDirectory()): Int {
        if (dir == null || !dir.isDirectory) return 0
        val txtFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) } ?: return 0
        return txtFiles.count { runCatching { it.delete() }.getOrDefault(false) }
    }
}

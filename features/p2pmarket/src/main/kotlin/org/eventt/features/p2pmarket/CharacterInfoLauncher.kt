package org.eventt.features.p2pmarket

import org.eventt.core.esi.EsiClient
import java.awt.Desktop
import java.net.URI

/**
 * Opens the EVE client's own "Show Info" window for a character by name, via the `showinfo:` URI
 * the game client registers as a protocol handler on the OS (same mechanism as clicking a showinfo
 * link on the EVE forums/wiki). Best-effort only — silently does nothing if the name doesn't
 * resolve to exactly one character or no handler is registered (e.g. the EVE client isn't
 * installed on this machine); this is a convenience shortcut, not a critical path.
 */
object CharacterInfoLauncher {
    fun openShowInfo(characterName: String) {
        val characterId =
            runCatching {
                EsiClient.search(characterName, listOf("character"), strict = true)["character"]?.singleOrNull()
            }.getOrNull() ?: return
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI("showinfo:1377//$characterId"))
            }
        }
    }
}

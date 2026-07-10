package org.eventt.features.p2pmarket

import org.eventt.core.esi.EsiClient
import org.eventt.core.nostr.NostrIdentityService

/**
 * Opens the character info ("Show Info") window for a trader in the running EVE client, via ESI's
 * `/ui/openwindow/information/` (the same "open this window in my running client" API
 * [org.eventt.core.esi.EsiClient.openMarketWindow] already uses for market windows). There's no OS
 * -level fallback — `showinfo:` links only resolve inside the EVE client's own browser/chat, never
 * as a protocol handler an external app can invoke, so this only ever works while the acting
 * character has a valid ESI token *and* is actually logged into a running client right now. Silently
 * does nothing otherwise; this is a convenience shortcut, not a critical path.
 */
object CharacterInfoLauncher {
    /**
     * [knownCharacterId] is the trader's real EVE character ID when the order/reservation that
     * carried it over Nostr included one — skips ESI name resolution entirely in that case. Falls
     * back to resolving [characterName] via ESI when it's null (e.g. an order from another client
     * that doesn't send an ID yet).
     */
    suspend fun openShowInfo(
        characterName: String,
        knownCharacterId: Int? = null,
    ): Boolean {
        val targetCharacterId = knownCharacterId ?: runCatching { EsiClient.resolveCharacterId(characterName) }.getOrNull() ?: return false
        val actingCharacterId = NostrIdentityService.getActiveIdentity()?.characterId ?: return false
        return runCatching { EsiClient.openCharacterInfoWindow(actingCharacterId, targetCharacterId) }.getOrDefault(false)
    }
}

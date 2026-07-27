package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Checked against github.com/nostr-protocol/nips (2026-07-27) — 30736 is unassigned (adjacent to
// our own already-used ORDER_KIND=30735), same due-diligence as ORDER_KIND/RECEIPT_KIND.
const val LEADERBOARD_KIND = 30736

/**
 * One entry per opted-in identity — addressable kind, so a republish just replaces it.
 *
 * v2: bumped from "eventt-leaderboard" to orphan stale entries published by the earlier
 * per-character opt-in design (superseded by the single-publisher-per-trader model) — old
 * relay-held events under the old tag are simply never matched by our filter anymore, no
 * migration needed since nothing durable depended on the old tag surviving.
 */
const val LEADERBOARD_D_TAG = "eventt-leaderboard-v2"

data class LeaderboardEntry(
    val pubkey: String,
    val traderChar: String,
    val traderCharId: Int?,
    val pnl7d: Double,
    val pnl30d: Double,
    val pnl365d: Double,
    val updatedAt: Long,
)

/**
 * Opt-in trader leaderboard (issue #17) — same NIP-33 addressable-event shape as P2P orders and
 * presence (kind + fixed `d` tag = latest created_at wins), published periodically (not live, see
 * [org.eventt.features.orders.LeaderboardPublisher]) by identities whose owner explicitly opted
 * in. Numbers are self-reported, same trust model as the rest of this app's P2P reputation — never
 * cryptographically verified against real ESI data.
 */
object LeaderboardService {
    private val _entries = MutableStateFlow<Map<String, LeaderboardEntry>>(emptyMap())

    /** Latest known leaderboard entry per pubkey (session-lifetime; repopulated from relays on start). */
    val entries: StateFlow<Map<String, LeaderboardEntry>> = _entries.asStateFlow()

    /**
     * Builds, signs, and publishes [identity]'s entry — the only entry point callers outside
     * core:nostr should use (keeps [NostrSignerSync]/[Event] out of features/orders' and
     * features/settings' code, same convention [OrderRepository] follows for orders).
     */
    suspend fun publishEntry(
        identity: NostrIdentity,
        pnl7d: Double,
        pnl30d: Double,
        pnl365d: Double,
        traderChar: String,
    ) {
        NostrRelayManager.publish(
            buildEntryEvent(
                QuartzGateway.signerFor(identity.keyPair),
                pnl7d,
                pnl30d,
                pnl365d,
                traderChar,
                identity.characterId,
            ),
        )
    }

    /** Publishes the tombstone and drops [identity]'s entry from the local aggregate — see [buildTombstone]/[onTombstone]. */
    suspend fun publishTombstone(identity: NostrIdentity) {
        NostrRelayManager.publish(buildTombstone(QuartzGateway.signerFor(identity.keyPair)))
        onTombstone(identity.pubkey)
    }

    fun buildEntryEvent(
        signer: NostrSignerSync,
        pnl7d: Double,
        pnl30d: Double,
        pnl365d: Double,
        traderChar: String,
        traderCharId: Int?,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val builder =
            TagArrayBuilder<Event>()
                .add(DTag(LEADERBOARD_D_TAG).toTagArray())
                .add(arrayOf("pnl_7d", pnl7d.toString()))
                .add(arrayOf("pnl_30d", pnl30d.toString()))
                .add(arrayOf("pnl_365d", pnl365d.toString()))
                .add(arrayOf("trader_char", traderChar))
        traderCharId?.let { builder.add(arrayOf("trader_char_id", it.toString())) }
        return QuartzGateway.signEvent(signer, createdAt, LEADERBOARD_KIND, builder.build(), "self-reported")
    }

    /**
     * NIP-09 deletion request, published when an identity's owner turns the opt-in off — same
     * best-effort-cleanup role as [NostrEventFactory.buildDeletionEvent] for orders. The `a` tag
     * addresses every past revision of this identity's entry, not one event id.
     */
    fun buildTombstone(
        signer: NostrSignerSync,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event =
        QuartzGateway.signEvent(
            signer,
            createdAt,
            5,
            arrayOf(
                arrayOf("a", "$LEADERBOARD_KIND:${signer.pubKey}:$LEADERBOARD_D_TAG"),
                arrayOf("k", LEADERBOARD_KIND.toString()),
            ),
            "",
        )

    /** Defensive parse — a malformed/unknown-shape event from another client is skipped (null), never crashes the app. */
    fun parseEntryEvent(event: Event): LeaderboardEntry? {
        if (event.kind != LEADERBOARD_KIND) return null
        if (runCatching { event.dTag() }.getOrNull() != LEADERBOARD_D_TAG) return null
        return runCatching {
            val tagsByName = event.tags.filter { it.size >= 2 }.groupBy({ it[0] }, { it[1] })

            fun tag(name: String) = tagsByName[name]?.firstOrNull()

            LeaderboardEntry(
                pubkey = event.pubKey,
                traderChar = tag("trader_char") ?: "",
                traderCharId = tag("trader_char_id")?.toIntOrNull(),
                pnl7d = requireNotNull(tag("pnl_7d")).toDouble(),
                pnl30d = requireNotNull(tag("pnl_30d")).toDouble(),
                pnl365d = requireNotNull(tag("pnl_365d")).toDouble(),
                updatedAt = event.createdAt,
            )
        }.getOrNull()
    }

    /** Called by [NostrRelayManager]'s leaderboard subscription. */
    fun onEvent(event: Event) {
        val parsed = parseEntryEvent(event) ?: return
        _entries.update { current ->
            val previous = current[parsed.pubkey]
            // Relays replay in arbitrary order — an older entry must not overwrite a newer one.
            if (previous != null && previous.updatedAt >= parsed.updatedAt) current else current + (parsed.pubkey to parsed)
        }
    }

    /**
     * Called directly by the Settings opt-out action right after publishing [buildTombstone] —
     * relays aren't subscribed for kind-5 here (best-effort cleanup only, same as orders), so this
     * is what actually makes our own entry disappear from the local aggregate right away instead
     * of lingering until it falls off some TTL that doesn't exist.
     */
    fun onTombstone(pubkey: String) {
        _entries.update { it - pubkey }
    }
}

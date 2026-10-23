package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToLong

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

// Cap on a foreign entry's daily series — anything longer is someone else's malformed event.
private const val MAX_DAILY_POINTS = 60

/**
 * What a trader publishes. Only the three P&L figures are required — everything else was added
 * later as optional tags, so an entry from an older client parses with them null/empty, and older
 * clients simply ignore tags they don't know.
 */
data class LeaderboardStats(
    val pnl7d: Double,
    val pnl30d: Double,
    val pnl365d: Double,
    // Realized profit / average ISK tied up in trading (escrow + stock at cost), percent.
    val roi7dPct: Double? = null,
    val roi30dPct: Double? = null,
    val roi365dPct: Double? = null,
    // Trade quality over the last 30 days.
    val winRate30dPct: Double? = null,
    val margin30dPct: Double? = null,
    val profitFactor30d: Double? = null,
    val sells30d: Int? = null,
    val turnover30d: Double? = null,
    // Realized P&L per day, oldest first, ending on the publish day.
    val daily30d: List<Double> = emptyList(),
    // Only when the trader opted in to sharing it.
    val netWorth: Double? = null,
)

data class LeaderboardEntry(
    val pubkey: String,
    val traderChar: String,
    val traderCharId: Int?,
    val stats: LeaderboardStats,
    val updatedAt: Long,
)

/** This machine's last successful publish: when, and exactly what went out. */
data class LeaderboardPublish(
    val at: Long,
    val traderChar: String,
    val stats: LeaderboardStats,
)

/**
 * Opt-in trader leaderboard (issue #17) — same NIP-33 addressable-event shape as P2P orders and
 * presence (kind + fixed `d` tag = latest created_at wins), published periodically (not live, see
 * [org.eventt.features.dashboard.LeaderboardPublisher]) by identities whose owner explicitly opted
 * in. Numbers are self-reported, same trust model as the rest of this app's P2P reputation — never
 * cryptographically verified against real ESI data.
 */
object LeaderboardService {
    private val _entries = MutableStateFlow<Map<String, LeaderboardEntry>>(emptyMap())

    /** Latest known leaderboard entry per pubkey (session-lifetime; repopulated from relays on start). */
    val entries: StateFlow<Map<String, LeaderboardEntry>> = _entries.asStateFlow()

    private val _lastPublish = MutableStateFlow<LeaderboardPublish?>(null)
    val lastPublish: StateFlow<LeaderboardPublish?> = _lastPublish.asStateFlow()

    // "Publish now" from the screen, consumed by the publisher's loop (which lives where the P&L
    // is computed); conflated — two quick clicks are one publish.
    private val _publishRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val publishRequests: SharedFlow<Unit> = _publishRequests.asSharedFlow()

    fun requestPublish() {
        _publishRequests.tryEmit(Unit)
    }

    /**
     * Builds, signs, and publishes [identity]'s entry — the only entry point callers outside
     * core:nostr should use (keeps [NostrSignerSync]/[Event] out of feature modules' code, same
     * convention [OrderRepository] follows for orders).
     */
    suspend fun publishEntry(
        identity: NostrIdentity,
        stats: LeaderboardStats,
        traderChar: String,
    ) {
        NostrRelayManager.publish(buildEntryEvent(QuartzGateway.signerFor(identity.keyPair), stats, traderChar, identity.characterId))
        _lastPublish.value = LeaderboardPublish(System.currentTimeMillis() / 1000, traderChar, stats)
    }

    /** Publishes the tombstone and drops [identity]'s entry from the local aggregate — see [buildTombstone]/[onTombstone]. */
    suspend fun publishTombstone(identity: NostrIdentity) {
        NostrRelayManager.publish(buildTombstone(QuartzGateway.signerFor(identity.keyPair)))
        onTombstone(identity.pubkey)
    }

    fun buildEntryEvent(
        signer: NostrSignerSync,
        stats: LeaderboardStats,
        traderChar: String,
        traderCharId: Int?,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val builder =
            TagArrayBuilder<Event>()
                .add(DTag(LEADERBOARD_D_TAG).toTagArray())
                .add(arrayOf("pnl_7d", stats.pnl7d.toString()))
                .add(arrayOf("pnl_30d", stats.pnl30d.toString()))
                .add(arrayOf("pnl_365d", stats.pnl365d.toString()))
                .add(arrayOf("trader_char", traderChar))
        traderCharId?.let { builder.add(arrayOf("trader_char_id", it.toString())) }

        fun opt(
            name: String,
            v: Any?,
        ) {
            if (v != null) builder.add(arrayOf(name, v.toString()))
        }
        opt("roi_7d", stats.roi7dPct)
        opt("roi_30d", stats.roi30dPct)
        opt("roi_365d", stats.roi365dPct)
        opt("win_rate_30d", stats.winRate30dPct)
        opt("margin_30d", stats.margin30dPct)
        opt("profit_factor_30d", stats.profitFactor30d)
        opt("sells_30d", stats.sells30d)
        opt("turnover_30d", stats.turnover30d)
        // Whole ISK is plenty for a sparkline and keeps the event small.
        if (stats.daily30d.isNotEmpty()) opt("pnl_daily_30d", stats.daily30d.joinToString(",") { it.roundToLong().toString() })
        opt("net_worth", stats.netWorth)
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

            // Optional tags parse leniently: a bad one is dropped, not the whole entry.
            fun num(name: String) = tag(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }

            LeaderboardEntry(
                pubkey = event.pubKey,
                traderChar = tag("trader_char") ?: "",
                traderCharId = tag("trader_char_id")?.toIntOrNull(),
                stats =
                    LeaderboardStats(
                        pnl7d = requireNotNull(tag("pnl_7d")).toDouble(),
                        pnl30d = requireNotNull(tag("pnl_30d")).toDouble(),
                        pnl365d = requireNotNull(tag("pnl_365d")).toDouble(),
                        roi7dPct = num("roi_7d"),
                        roi30dPct = num("roi_30d"),
                        roi365dPct = num("roi_365d"),
                        winRate30dPct = num("win_rate_30d"),
                        margin30dPct = num("margin_30d"),
                        profitFactor30d = num("profit_factor_30d"),
                        sells30d = tag("sells_30d")?.toIntOrNull(),
                        turnover30d = num("turnover_30d"),
                        daily30d =
                            tag("pnl_daily_30d")
                                ?.split(",")
                                ?.mapNotNull { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } }
                                ?.take(MAX_DAILY_POINTS)
                                .orEmpty(),
                        netWorth = num("net_worth"),
                    ),
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

package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigInteger
import java.security.MessageDigest

// Next to LEADERBOARD_KIND; unassigned in github.com/nostr-protocol/nips like the other two.
const val GIVEAWAY_KIND = 30737
const val GIVEAWAY_T_TAG = "eventt-giveaway"

/**
 * The only key whose giveaway events count — the organiser's (Sasha Winston's leaderboard
 * identity). Anyone can sign a kind-30737 event, so clients subscribe to this author only and
 * check the signature too; a rebuilt client showing the organiser's buttons gets nowhere.
 */
const val GIVEAWAY_ADMIN_PUBKEY = "f1deceee163a51feb95da4861beaa4ac6f4f33ec54baf0bec9173d1db539dadc"

enum class GiveawayStatus { ANNOUNCED, CLOSED, DRAWN, CANCELLED }

data class GiveawayParticipant(
    val pubkey: String,
    val name: String,
)

/**
 * One PLEX giveaway among leaderboard traders. One addressable event per giveaway ([id] is its d
 * tag), republished as it moves on: announced → closed (participants frozen, a future Bitcoin
 * block named) → drawn (that block's hash and the winners) — or cancelled.
 */
data class Giveaway(
    val id: String,
    val status: GiveawayStatus,
    val plexPerWinner: Int,
    val winnerCount: Int,
    // Eligible: published a leaderboard entry within this many days, with sales.
    val activeDays: Int,
    // Planned draw time (unix seconds), as announced.
    val drawAt: Long,
    val note: String,
    val participants: List<GiveawayParticipant> = emptyList(),
    val btcHeight: Int? = null,
    val btcHash: String? = null,
    val winners: List<GiveawayParticipant> = emptyList(),
    // A dry run by the organiser: their own characters take part, and other clients hide it.
    val test: Boolean = false,
    val updatedAt: Long = 0,
)

object GiveawayService {
    private val _giveaways = MutableStateFlow<Map<String, Giveaway>>(emptyMap())

    /** Latest revision of every giveaway the organiser published, by id. */
    val giveaways: StateFlow<Map<String, Giveaway>> = _giveaways.asStateFlow()

    /** Signs and publishes [g] with the organiser's identity — callers check [isAdmin] first. */
    suspend fun publish(
        identity: NostrIdentity,
        g: Giveaway,
    ) {
        val event = buildEvent(QuartzGateway.signerFor(identity.keyPair), g)
        NostrRelayManager.publish(event)
        onEvent(event)
    }

    fun buildEvent(
        signer: NostrSignerSync,
        g: Giveaway,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Event {
        val tags =
            buildList {
                add(arrayOf("d", g.id))
                add(arrayOf("t", GIVEAWAY_T_TAG))
                add(arrayOf("status", g.status.name.lowercase()))
                add(arrayOf("prize_plex", g.plexPerWinner.toString()))
                add(arrayOf("winners", g.winnerCount.toString()))
                add(arrayOf("active_days", g.activeDays.toString()))
                add(arrayOf("draw_at", g.drawAt.toString()))
                g.btcHeight?.let { add(arrayOf("btc_height", it.toString())) }
                g.btcHash?.let { add(arrayOf("btc_hash", it)) }
                if (g.test) add(arrayOf("test", "1"))
                g.participants.forEach { add(arrayOf("participant", it.pubkey, it.name)) }
                g.winners.forEach { add(arrayOf("winner", it.pubkey, it.name)) }
            }
        return QuartzGateway.signEvent(signer, createdAt, GIVEAWAY_KIND, tags.toTypedArray(), g.note)
    }

    /** Null for anything not a well-formed giveaway signed by the organiser. */
    fun parseEvent(
        event: Event,
        admin: String = GIVEAWAY_ADMIN_PUBKEY,
    ): Giveaway? {
        if (event.kind != GIVEAWAY_KIND || event.pubKey != admin) return null
        if (!runCatching { event.verify() }.getOrDefault(false)) return null
        return runCatching {
            fun tag(name: String) = event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

            fun people(name: String) = event.tags.filter { it.size >= 3 && it[0] == name }.map { GiveawayParticipant(it[1], it[2]) }
            Giveaway(
                id = requireNotNull(event.dTag().takeIf { it.isNotEmpty() }),
                status = GiveawayStatus.valueOf(requireNotNull(tag("status")).uppercase()),
                plexPerWinner = requireNotNull(tag("prize_plex")).toInt(),
                winnerCount = requireNotNull(tag("winners")).toInt(),
                activeDays = requireNotNull(tag("active_days")).toInt(),
                drawAt = requireNotNull(tag("draw_at")).toLong(),
                note = event.content,
                participants = people("participant"),
                btcHeight = tag("btc_height")?.toIntOrNull(),
                btcHash = tag("btc_hash"),
                winners = people("winner"),
                test = tag("test") == "1",
                updatedAt = event.createdAt,
            )
        }.getOrNull()
    }

    /** Called by [NostrRelayManager]'s giveaway subscription. */
    fun onEvent(event: Event) {
        val g = parseEvent(event) ?: return
        _giveaways.update { current ->
            val previous = current[g.id]
            if (previous != null && previous.updatedAt >= g.updatedAt) current else current + (g.id to g)
        }
    }

    /**
     * The winners, from nothing but public data: [blockHash] (unknown to anyone, the organiser
     * included, when the participant list was frozen), the giveaway [id] and the participants.
     * Every client recomputes this to check the organiser's published result.
     */
    fun drawWinners(
        participants: List<GiveawayParticipant>,
        blockHash: String,
        id: String,
        count: Int,
    ): List<GiveawayParticipant> {
        val pool = participants.distinctBy { it.pubkey }.sortedBy { it.pubkey }.toMutableList()
        val seed = sha256("$blockHash:$id".toByteArray())
        return (0 until minOf(count, pool.size)).map { i ->
            // 256-bit pick, so the modulo bias is nil for any realistic pool.
            val pick = BigInteger(1, sha256(seed + i.toString().toByteArray())).mod(BigInteger.valueOf(pool.size.toLong()))
            pool.removeAt(pick.toInt())
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}

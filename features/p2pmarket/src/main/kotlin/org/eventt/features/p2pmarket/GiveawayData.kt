package org.eventt.features.p2pmarket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eventt.core.database.CharacterDao
import org.eventt.core.model.AppLog
import org.eventt.core.nostr.GIVEAWAY_ADMIN_PUBKEY
import org.eventt.core.nostr.Giveaway
import org.eventt.core.nostr.GiveawayParticipant
import org.eventt.core.nostr.GiveawayService
import org.eventt.core.nostr.GiveawayStatus
import org.eventt.core.nostr.LeaderboardEntry
import org.eventt.core.nostr.LeaderboardService
import org.eventt.core.nostr.NostrIdentityService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Blocks a closed giveaway waits for: ~20 min of Bitcoin, enough that nobody knows the hash when
// the participant list is frozen.
internal const val BLOCKS_AHEAD = 2

/**
 * Who is in: published a leaderboard entry within [activeDays] with some sales (old clients don't
 * send a sales count, so a non-zero 30-day P&L counts instead), one ticket per character, never
 * the organiser's own identities or characters.
 */
internal fun eligible(
    entries: Collection<LeaderboardEntry>,
    activeDays: Int,
    nowSec: Long,
    excludePubkeys: Set<String>,
    excludeCharIds: Set<Int>,
): List<GiveawayParticipant> =
    entries
        .filter { nowSec - it.updatedAt <= activeDays * 86400L }
        .filter { (it.stats.sells30d ?: if (it.stats.pnl30d != 0.0) 1 else 0) > 0 }
        .filter { it.pubkey !in excludePubkeys && it.traderCharId !in excludeCharIds }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.traderCharId ?: it.pubkey }
        .map { GiveawayParticipant(it.pubkey, it.traderChar) }
        .sortedBy { it.name.lowercase() }

// Test giveaways are the organiser's dry runs: nobody else sees them.
private fun Giveaway.visibleTo(isAdmin: Boolean) = !test || isAdmin

/** The giveaway to show: the newest one not cancelled. */
internal fun currentGiveaway(
    all: Collection<Giveaway>,
    isAdmin: Boolean,
): Giveaway? =
    all
        .filter { it.status != GiveawayStatus.CANCELLED && it.visibleTo(isAdmin) }
        .maxByOrNull { it.id.removePrefix("g-").toLongOrNull() ?: 0L }

internal data class Wins(
    val count: Int,
    val plex: Long,
)

/** Every trader's wins over all drawn giveaways, by pubkey. */
internal fun wins(
    all: Collection<Giveaway>,
    isAdmin: Boolean,
): Map<String, Wins> =
    all
        .filter { it.status == GiveawayStatus.DRAWN && it.visibleTo(isAdmin) }
        .flatMap { g -> g.winners.map { it.pubkey to g.plexPerWinner.toLong() } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, prizes) -> Wins(prizes.size, prizes.sum()) }

/** Winners of the newest drawn giveaway — the ones shown in gold, until the next draw. */
internal fun lastWinners(
    all: Collection<Giveaway>,
    isAdmin: Boolean,
): Set<String> =
    all
        .filter { it.status == GiveawayStatus.DRAWN && it.visibleTo(isAdmin) }
        .maxByOrNull { it.id.removePrefix("g-").toLongOrNull() ?: 0L }
        ?.winners
        ?.map { it.pubkey }
        ?.toSet()
        .orEmpty()

/** Freezes the participants and names a Bitcoin block still to come. Blocking (network). */
internal fun closeGiveaway(
    g: Giveaway,
    entries: Collection<LeaderboardEntry>,
    nowSec: Long,
    excludePubkeys: Set<String>,
    excludeCharIds: Set<Int>,
): Giveaway {
    val participants =
        if (g.test) {
            eligible(
                entries,
                g.activeDays,
                nowSec,
                emptySet(),
                emptySet(),
            )
        } else {
            eligible(entries, g.activeDays, nowSec, excludePubkeys, excludeCharIds)
        }
    if (participants.isEmpty()) return g.copy(status = GiveawayStatus.CANCELLED)
    val height = requireNotNull(BitcoinBlocks.tip()?.height) { "no Bitcoin block height" }
    return g.copy(status = GiveawayStatus.CLOSED, participants = participants, btcHeight = height + BLOCKS_AHEAD)
}

/** The drawn giveaway once its block is out, else null. Blocking (network). */
internal fun drawGiveaway(g: Giveaway): Giveaway? {
    val hash = BitcoinBlocks.hashAt(g.btcHeight ?: return null) ?: return null
    return g.copy(
        status = GiveawayStatus.DRAWN,
        btcHash = hash,
        winners = GiveawayService.drawWinners(g.participants, hash, g.id, g.winnerCount),
    )
}

/**
 * On the organiser's machine only: closes an announced giveaway when its time comes and publishes
 * the results once the named block is out — whichever screen is open, as long as the app runs.
 */
object GiveawayAutomation {
    private const val TICK_MS = 10_000L

    fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                runCatching { tick() }.onFailure { AppLog.warn("Giveaway", "automation", it) }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        val admin = NostrIdentityService.getIdentityByPubkey(GIVEAWAY_ADMIN_PUBKEY) ?: return
        val now = System.currentTimeMillis() / 1000
        for (g in GiveawayService.giveaways.value.values) {
            val next =
                when {
                    g.status == GiveawayStatus.ANNOUNCED && now >= g.drawAt -> {
                        val myPubkeys = NostrIdentityService.listIdentities().map { it.pubkey }.toSet()
                        val myCharIds = CharacterDao.getAll().map { it.id }.toSet()
                        closeGiveaway(g, LeaderboardService.entries.value.values, now, myPubkeys, myCharIds)
                    }

                    g.status == GiveawayStatus.CLOSED -> {
                        drawGiveaway(g)
                    }

                    else -> {
                        null
                    }
                } ?: continue
            GiveawayService.publish(admin, next)
        }
    }
}

internal data class BlockTip(
    val height: Int,
    // Unix seconds the block was mined.
    val time: Long,
)

/** Public Bitcoin block data — two independent explorers with the same API, so one being down doesn't stall a draw. */
internal object BitcoinBlocks {
    private val hosts = listOf("https://mempool.space/api", "https://blockstream.info/api")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** The newest block — the first of the `/blocks` list both explorers serve. */
    fun tip(): BlockTip? {
        val json = get("/blocks") ?: return null
        val height =
            Regex("\"height\":(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        val time =
            Regex("\"timestamp\":(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: return null
        return BlockTip(height, time)
    }

    /** Null until the block exists. */
    fun hashAt(height: Int): String? = get("/block-height/$height")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    private fun get(path: String): String? =
        hosts.firstNotNullOfOrNull { host ->
            runCatching {
                val res =
                    http.send(
                        HttpRequest.newBuilder(URI("$host$path")).timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                res.body().trim().takeIf { res.statusCode() == 200 }
            }.onFailure { AppLog.warn("Giveaway", "bitcoin $host$path", it) }.getOrNull()
        }
}

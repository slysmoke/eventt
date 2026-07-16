package org.eventt.features.orders

import org.eventt.core.database.MarketTopSnapshot
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns a window of top-of-book snapshots (one per ~5-min ESI tick, see MarketTopSnapshotDao)
 * into per-order competition metrics. Nothing faster than the tick grid is observable — and
 * nothing faster matters, since in-game order modification is limited to the same 5 minutes.
 * The bot discriminator is therefore not reaction *speed* (a no-lifer and a bot both look like
 * "next tick") but round-the-clock *coverage*: humans sleep, bots respond at 04:00 too.
 */
object CompetitionService {
    enum class Level { COLLECTING, CALM, CONTESTED, BOT_WAR }

    data class Stats(
        /** Snapshots in the window — the sample size behind everything else. */
        val ticks: Int,
        /** Share of ticks where my order was the best price — the share of time I'm actually selling. */
        val timeOnTopPct: Double,
        /** Distinct competing order ids seen at the top. Price edits keep the order id, so this ≈ people (upper bound: relists mint new ids). */
        val competitors: Int,
        /** Median ticks I stay on top before someone retakes it. Null if never beaten in-window. */
        val medianBeatTicks: Double?,
        /** Of the times I was beaten, the share where it happened by the very next tick. */
        val fastBeatShare: Double?,
        /** Distinct UTC hours-of-day in which a beat occurred — 20+ means someone answers around the clock. */
        val beatHourCoverage: Int,
        val level: Level,
    )

    // ~1h of ticks before we claim anything.
    private const val MIN_TICKS = 12

    private const val CALM_TIME_ON_TOP = 0.6
    private const val BOT_FAST_BEAT_SHARE = 0.8

    // Out of 24 — a human undercutter concentrated in a play session covers far fewer.
    private const val BOT_HOUR_COVERAGE = 16

    fun compute(snapshots: List<MarketTopSnapshot>): Stats {
        val ticks = snapshots.size
        if (ticks < MIN_TICKS) return Stats(ticks, 0.0, 0, null, null, 0, Level.COLLECTING)

        val ordered = snapshots.sortedBy { it.ts }
        val timeOnTopPct = ordered.count { it.bestIsMine }.toDouble() / ticks
        val competitors =
            ordered
                .filter { !it.bestIsMine }
                .map { it.bestOrderId }
                .distinct()
                .size

        // Each maximal run of "mine on top" that ends with someone retaking the top is one beat:
        // its length = how many ticks I lasted, the retaking tick's hour = when they answered.
        // A run still on top at the window's end is censored (not beaten yet), so it's skipped.
        val beatRunLengths = mutableListOf<Int>()
        val beatHours = mutableSetOf<Int>()
        var run = 0
        for (snap in ordered) {
            if (snap.bestIsMine) {
                run++
            } else {
                if (run > 0) {
                    beatRunLengths.add(run)
                    beatHours.add(Instant.ofEpochMilli(snap.ts).atZone(ZoneOffset.UTC).hour)
                }
                run = 0
            }
        }

        val medianBeatTicks = beatRunLengths.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble() }
        val fastBeatShare = if (beatRunLengths.isEmpty()) null else beatRunLengths.count { it <= 1 }.toDouble() / beatRunLengths.size

        val level =
            when {
                competitors == 0 || timeOnTopPct >= CALM_TIME_ON_TOP -> Level.CALM
                (fastBeatShare ?: 0.0) >= BOT_FAST_BEAT_SHARE && beatHours.size >= BOT_HOUR_COVERAGE -> Level.BOT_WAR
                else -> Level.CONTESTED
            }
        return Stats(ticks, timeOnTopPct, competitors, medianBeatTicks, fastBeatShare, beatHours.size, level)
    }
}

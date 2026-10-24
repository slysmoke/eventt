package org.eventt.features.market

import org.eventt.core.model.MarketHistoryModel
import java.time.Instant
import java.time.LocalDate

/** A trade hub: its region and the one station where the hub's trading actually happens. */
internal data class Hub(
    val regionId: Int,
    val name: String,
    val stationId: Long,
)

internal val HUBS =
    listOf(
        Hub(10000002, "Jita", 60003760L),
        Hub(10000043, "Amarr", 60008494L),
        Hub(10000032, "Dodixie", 60011866L),
        Hub(10000030, "Hek", 60005686L),
        Hub(10000042, "Rens", 60004588L),
    )

internal data class MarketOrder(
    val orderId: Long,
    val price: Double,
    val volumeRemaining: Int,
    val volumeTotal: Int,
    val minVolume: Int,
    val isBuyOrder: Boolean,
    val locationId: Long,
    val locationName: String,
    val systemId: Int,
    val issued: String,
    val duration: Int,
    val range: String,
) {
    fun expiresAt(): Instant? = runCatching { Instant.parse(issued).plusSeconds(duration * 86400L) }.getOrNull()
}

/** A book row: the order plus the volume stacked from the best price down to it, inclusive. */
internal data class BookRow(
    val o: MarketOrder,
    val cumulative: Long,
)

// Sells cheapest first, buys dearest first — the order the volume stacks up in from the spread.
internal fun stack(
    orders: List<MarketOrder>,
    isBuy: Boolean,
): List<BookRow> {
    var acc = 0L
    return (if (isBuy) orders.sortedByDescending { it.price } else orders.sortedBy { it.price })
        .map { BookRow(it, (acc + it.volumeRemaining).also { v -> acc = v }) }
}

internal data class MarketStats(
    val bestAsk: Double?,
    val bestBid: Double?,
    val spreadPct: Double?,
    val askOrders: Int,
    val askVolume: Long,
    val bidOrders: Int,
    val bidVolume: Long,
    val avg30: Double?,
    val low30: Double?,
    val high30: Double?,
    val volPerDay30: Double,
    // Where the mid price sits against the 30-day average, percent.
    val midVsAvgPct: Double?,
)

internal fun marketStats(
    sells: List<MarketOrder>,
    buys: List<MarketOrder>,
    history: List<MarketHistoryModel>,
    today: LocalDate,
): MarketStats {
    val ask = sells.minOfOrNull { it.price }
    val bid = buys.maxOfOrNull { it.price }
    val cutoff = today.minusDays(30).toString()
    val recent = history.filter { it.date.take(10) >= cutoff }
    val avg = recent.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.average } / r.size }
    val mid = if (ask != null && bid != null) (ask + bid) / 2 else ask ?: bid
    return MarketStats(
        bestAsk = ask,
        bestBid = bid,
        spreadPct = if (ask != null && bid != null && ask > 0) (ask - bid) / ask * 100 else null,
        askOrders = sells.size,
        askVolume = sells.sumOf { it.volumeRemaining.toLong() },
        bidOrders = buys.size,
        bidVolume = buys.sumOf { it.volumeRemaining.toLong() },
        avg30 = avg,
        low30 = recent.minOfOrNull { it.lowest },
        high30 = recent.maxOfOrNull { it.highest },
        // Calendar days, not trading days: a quiet item's empty days count as zero volume.
        volPerDay30 = recent.sumOf { it.volume } / 30.0,
        midVsAvgPct = if (mid != null && avg != null && avg > 0) (mid - avg) / avg * 100 else null,
    )
}

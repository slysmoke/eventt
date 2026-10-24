package org.eventt.features.contracts

import org.eventt.core.model.ContractModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// Status tabs: what still needs watching, what went through, what didn't, everything.
internal enum class StatusGroup { ACTIVE, FINISHED, CLOSED, ALL }

internal fun statusGroup(status: String): StatusGroup =
    when {
        status == "outstanding" || status == "in_progress" -> StatusGroup.ACTIVE
        status.startsWith("finished") -> StatusGroup.FINISHED
        else -> StatusGroup.CLOSED
    }

// ESI dates come as "2026-07-10T01:48:44Z" (stored with or without the Z) — always UTC.
internal fun parseEsiTime(s: String?): Instant? =
    s?.takeIf { it.length >= 19 }?.let { runCatching { LocalDateTime.parse(it.take(19)).toInstant(ZoneOffset.UTC) }.getOrNull() }

/** One table row: the contract plus everything derived from it for display. */
internal data class ContractLine(
    val c: ContractModel,
    val from: String,
    val to: String,
    // The other side: whoever accepted (or was assigned) when one of ours issued it, else the issuer.
    val party: String?,
    val owner: String?,
) {
    val group get() = statusGroup(c.status)
    val issued get() = parseEsiTime(c.dateIssued)
    val expires get() = parseEsiTime(c.dateExpired)
    val accepted get() = parseEsiTime(c.dateAccepted)
    val completed get() = parseEsiTime(c.dateCompleted)
    val isCourier get() = c.type == "courier"

    // What the contract is worth: the reward for a courier, the asking price otherwise.
    val value get() = if (isCourier) c.reward else c.price

    // Issued → completed (finished), issued → now (still active), null for the rest.
    fun age(now: Instant): Duration? {
        val start = issued ?: return null
        val end = if (group == StatusGroup.ACTIVE) now else completed ?: return null
        return Duration.between(start, end).takeIf { !it.isNegative }
    }

    val acceptDelay: Duration? get() = issued?.let { i -> accepted?.let { Duration.between(i, it) } }

    // An outstanding contract past its expiry date — ESI only flips it to "expired"-ish states later.
    fun isExpired(now: Instant) = c.status == "outstanding" && expires?.isBefore(now) == true

    val search get() = listOfNotNull(c.title, from, to, party, owner, c.type).joinToString(" ")
}

// Courier/exchange stats cover the whole stored history (contracts are bursty — a quiet month
// would zero out a 30-day figure); the 30-day part is shown alongside.
internal data class ContractTotals(
    val active: Int,
    val inTransit: Int,
    val collateralInTransit: Double,
    val collateralOutstanding: Double,
    val rewards: Double,
    val couriers: Int,
    val rewards30d: Double,
    val exchange: Double,
    val exchanges: Int,
    val exchange30d: Double,
    val avgDelivery: Duration?,
    val avgAccept: Duration?,
    val expiring24h: Int,
    val rewardToCollateralPct: Double?,
)

internal fun totals(
    lines: List<ContractLine>,
    now: Instant,
): ContractTotals {
    val since = now.minus(Duration.ofDays(30))
    val done = lines.filter { it.group == StatusGroup.FINISHED }
    val couriers = done.filter { it.isCourier }
    val exchanges = done.filter { !it.isCourier }

    fun List<ContractLine>.recent() = filter { (it.completed ?: it.issued)?.isAfter(since) == true }
    val inTransit = lines.filter { it.isCourier && it.c.status == "in_progress" }
    val outstanding = lines.filter { it.c.status == "outstanding" && !it.isExpired(now) }
    val collateral = couriers.sumOf { it.c.collateral }
    return ContractTotals(
        active = lines.count { it.group == StatusGroup.ACTIVE },
        inTransit = inTransit.size,
        collateralInTransit = inTransit.sumOf { it.c.collateral },
        collateralOutstanding = outstanding.filter { it.isCourier }.sumOf { it.c.collateral },
        rewards = couriers.sumOf { it.c.reward },
        couriers = couriers.size,
        rewards30d = couriers.recent().sumOf { it.c.reward },
        exchange = exchanges.sumOf { it.c.price },
        exchanges = exchanges.size,
        exchange30d = exchanges.recent().sumOf { it.c.price },
        avgDelivery = couriers.mapNotNull { it.age(now) }.average(),
        avgAccept = couriers.mapNotNull { it.acceptDelay }.average(),
        expiring24h = outstanding.count { it.expires?.isBefore(now.plus(Duration.ofDays(1))) == true },
        rewardToCollateralPct = if (collateral > 0) couriers.sumOf { it.c.reward } / collateral * 100 else null,
    )
}

private fun List<Duration>.average(): Duration? = if (isEmpty()) null else Duration.ofSeconds(sumOf { it.seconds } / size)

/** One courier lane (or one counterparty): how often, how much, how fast. */
internal data class LaneStat(
    val label: String,
    val count: Int,
    val value: Double,
    val collateral: Double,
    val avgTime: Duration?,
)

internal fun lanes(
    lines: List<ContractLine>,
    now: Instant,
): List<LaneStat> =
    lines
        .filter { it.isCourier }
        .groupBy { "${it.from} → ${it.to}" }
        .map { (k, l) -> LaneStat(k, l.size, l.sumOf { it.value }, l.sumOf { it.c.collateral }, l.mapNotNull { it.age(now) }.average()) }
        .sortedByDescending { it.count }

internal fun parties(
    lines: List<ContractLine>,
    now: Instant,
): List<LaneStat> =
    lines
        .filter { it.party != null }
        .groupBy { it.party!! }
        .map { (k, l) -> LaneStat(k, l.size, l.sumOf { it.value }, l.sumOf { it.c.collateral }, l.mapNotNull { it.age(now) }.average()) }
        .sortedByDescending { it.count }

// "3d 4h", "5h 12m", "8m".
internal fun formatSpan(d: Duration): String =
    when {
        d.toDays() > 0 -> "${d.toDays()}d ${d.toHoursPart()}h"
        d.toHours() > 0 -> "${d.toHours()}h ${d.toMinutesPart()}m"
        else -> "${d.toMinutes()}m"
    }

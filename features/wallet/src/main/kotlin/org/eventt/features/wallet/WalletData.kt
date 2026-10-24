package org.eventt.features.wallet

import org.eventt.features.orders.CostBasisService
import java.time.LocalDate

/** A market transaction row, typed from WalletDao's map. */
internal data class Tx(
    val id: Long,
    val date: String,
    val typeId: Int,
    val typeName: String,
    val qty: Int,
    val unitPrice: Double,
    val total: Double,
    val isBuy: Boolean,
    val client: String,
    val station: String,
) {
    val day get() = date.take(10)
    val search get() = "$typeName $client $station"
}

internal fun Map<String, Any?>.toTx(): Tx {
    val unitPrice = (this["unit_price"] as? Number)?.toDouble() ?: 0.0
    val qty = (this["quantity"] as? Number)?.toInt() ?: 0
    val typeId = (this["type_id"] as? Number)?.toInt() ?: 0
    return Tx(
        id = (this["transaction_id"] as? Number)?.toLong() ?: 0,
        date = this["date"]?.toString().orEmpty(),
        typeId = typeId,
        typeName = this["type_name"]?.toString()?.ifEmpty { null } ?: "#$typeId",
        qty = qty,
        unitPrice = unitPrice,
        total = (this["total"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: (unitPrice * qty),
        isBuy = this["is_buy"] as? Boolean ?: false,
        client = this["client_name"]?.toString()?.ifEmpty { null } ?: this["client_id"]?.let { "#$it" }.orEmpty(),
        station = this["location_name"]?.toString()?.ifEmpty { null } ?: this["location_id"]?.toString().orEmpty(),
    )
}

/** A journal row, typed from WalletDao's map. */
internal data class Entry(
    val id: Long,
    val date: String,
    val refType: String,
    val reason: String,
    val amount: Double,
    val tax: Double?,
    val balance: Double,
) {
    val day get() = date.take(10)
}

internal fun Map<String, Any?>.toEntry() =
    Entry(
        id = (this["entry_id"] as? Number)?.toLong() ?: 0,
        date = this["date"]?.toString().orEmpty(),
        refType = this["ref_type"]?.toString().orEmpty(),
        reason = this["reason"]?.toString()?.trim().orEmpty(),
        amount = (this["amount"] as? Number)?.toDouble() ?: 0.0,
        tax = (this["tax_amount"] as? Number)?.toDouble()?.takeIf { it != 0.0 },
        balance = (this["balance"] as? Number)?.toDouble() ?: 0.0,
    )

// Journal ref types that are the cost of trading rather than trading itself.
internal fun isFee(refType: String) = refType.endsWith("_tax") || refType.contains("brokers_fee")

/** Days back from today for the period switch; null = everything. */
internal enum class Period(
    val days: Long?,
) {
    D7(7),
    D30(30),
    D90(90),
    ALL(null),
    ;

    fun since(today: LocalDate): String? = days?.let { today.minusDays(it - 1).toString() }
}

internal fun <T> List<T>.inPeriod(
    p: Period,
    today: LocalDate,
    day: (T) -> String,
): List<T> {
    val since = p.since(today) ?: return this
    return filter { day(it) >= since }
}

internal data class WalletTotals(
    val sales: Double,
    val sells: Int,
    val purchases: Double,
    val buys: Int,
    val fees: Double,
    val cashFlow: Double,
    val realized: Double?,
    val items: Int,
)

internal fun walletTotals(
    tx: List<Tx>,
    journal: List<Entry>,
    fifo: CostBasisService.FifoResult?,
    p: Period,
    today: LocalDate,
): WalletTotals {
    val t = tx.inPeriod(p, today) { it.day }
    val j = journal.inPeriod(p, today) { it.day }
    return WalletTotals(
        sales = t.filter { !it.isBuy }.sumOf { it.total },
        sells = t.count { !it.isBuy },
        purchases = t.filter { it.isBuy }.sumOf { it.total },
        buys = t.count { it.isBuy },
        fees = -j.filter { isFee(it.refType) }.sumOf { it.amount },
        cashFlow = j.sumOf { it.amount },
        realized = fifo?.realizedSells?.inPeriod(p, today) { it.date.take(10) }?.sumOf { it.profit },
        items = t.map { it.typeId }.distinct().size,
    )
}

/** Per item or per client: what went through, both ways. */
internal data class FlowStat(
    val key: String,
    val label: String,
    val typeId: Int?,
    val bought: Double,
    val sold: Double,
    val trades: Int,
    val realized: Double?,
) {
    val turnover get() = bought + sold
}

internal fun byItem(
    tx: List<Tx>,
    realizedByType: Map<Int, Double>?,
): List<FlowStat> =
    tx
        .groupBy { it.typeId }
        .map { (id, l) ->
            FlowStat(
                id.toString(),
                l.first().typeName,
                id,
                l.filter { it.isBuy }.sumOf { it.total },
                l.filter { !it.isBuy }.sumOf { it.total },
                l.size,
                realizedByType?.get(id),
            )
        }.sortedByDescending { it.turnover }

internal fun byClient(tx: List<Tx>): List<FlowStat> =
    tx
        .groupBy { it.client }
        .map { (c, l) ->
            FlowStat(c, c, null, l.filter { it.isBuy }.sumOf { it.total }, l.filter { !it.isBuy }.sumOf { it.total }, l.size, null)
        }.sortedByDescending { it.turnover }

internal fun realizedByType(
    fifo: CostBasisService.FifoResult?,
    p: Period,
    today: LocalDate,
): Map<Int, Double>? =
    fifo
        ?.realizedSells
        ?.inPeriod(p, today) { it.date.take(10) }
        ?.groupBy { it.typeId }
        ?.mapValues { (_, l) -> l.sumOf { it.profit } }

/** One journal ref type: how often and how much. */
internal data class RefStat(
    val refType: String,
    val count: Int,
    val sum: Double,
)

internal fun byRefType(journal: List<Entry>): List<RefStat> =
    journal
        .groupBy { it.refType }
        .map { (r, l) -> RefStat(r, l.size, l.sumOf { it.amount }) }
        .sortedByDescending { kotlin.math.abs(it.sum) }

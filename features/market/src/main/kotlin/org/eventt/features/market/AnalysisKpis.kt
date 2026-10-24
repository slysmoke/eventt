package org.eventt.features.market

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.eventt.market.generated.resources.*
import org.eventt.ui.common.Kpi
import org.eventt.ui.common.formatIsk
import org.eventt.ui.common.formatVolume
import org.eventt.ui.common.outline
import org.eventt.ui.common.pnlColor
import org.eventt.ui.common.signed
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

// What a result list adds up to at the quantities the table suggests: the summary strip over the
// Station Trading and Inter-Region tables.
internal data class OpportunityTotals(
    val count: Int,
    val dailyProfit: Double,
    val capital: Double,
    val medianMargin: Double,
    val best: String?,
    val bestProfit: Double,
    val cargoM3: Double = 0.0,
)

private fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

internal fun stationTotals(
    list: List<StationOpportunity>,
    volCapEnabled: Boolean,
    volCapPct: Double,
): OpportunityTotals {
    val daily = list.map { it to it.netProfit * stationEffVol(it, volCapEnabled, volCapPct) }
    val best = daily.maxByOrNull { it.second }
    return OpportunityTotals(
        count = list.size,
        dailyProfit = daily.sumOf { it.second },
        capital = list.sumOf { it.bestBuy * stationEffVol(it, volCapEnabled, volCapPct) },
        medianMargin = median(list.map { it.marginPct }),
        best = best?.first?.typeName,
        bestProfit = best?.second ?: 0.0,
    )
}

internal fun regionTotals(
    list: List<RegionOpportunity>,
    tradeType: InterRegionTradeType,
    volCapEnabled: Boolean,
    volCapPct: Double,
): OpportunityTotals {
    val daily = list.map { it to regionDailyProfit(it, tradeType, volCapEnabled, volCapPct) }
    val best = daily.maxByOrNull { it.second }
    val qty = list.associateWith { regionFinalVol(it, tradeType, volCapEnabled, volCapPct) }
    return OpportunityTotals(
        count = list.size,
        dailyProfit = daily.sumOf { it.second },
        capital = list.sumOf { (it.buyPrice + it.shippingCostPerUnit) * qty.getValue(it) },
        medianMargin = median(list.map { it.marginPct }),
        best = best?.first?.typeName,
        bestProfit = best?.second ?: 0.0,
        cargoM3 = list.sumOf { it.itemVolumeM3 * qty.getValue(it) },
    )
}

@Composable
private fun Strip(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall),
        content = content,
    )
}

@Composable
internal fun OpportunityKpis(t: OpportunityTotals) {
    Strip {
        Kpi(stringResource(Res.string.kpi_found), t.count.toString(), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_est_daily),
            formatIsk(t.dailyProfit),
            null,
            null,
            null,
            Res.string.tip_kpi_est_daily,
            valueColor = pnlColor(t.dailyProfit),
        )
        Kpi(stringResource(Res.string.kpi_capital), formatIsk(t.capital), null, null, null, Res.string.tip_kpi_capital)
        if (t.cargoM3 > 0) Kpi(stringResource(Res.string.kpi_cargo), formatVolume(t.cargoM3) + " m³", null, null, null)
        Kpi(stringResource(Res.string.kpi_median_margin), String.format(Locale.US, "%.1f%%", t.medianMargin), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_best),
            t.best ?: "—",
            t.best?.let { "+" + formatIsk(t.bestProfit) },
            positiveColor,
            null,
            last = true,
        )
    }
}

@Composable
internal fun MaterialsKpis(
    allocated: List<AllocatedMaterial>,
    budget: Double,
) {
    val held = allocated.mapNotNull { it.sellTarget?.profitNow }
    Strip {
        Kpi(stringResource(Res.string.kpi_found), allocated.size.toString(), null, null, null)
        Kpi(
            stringResource(Res.string.kpi_buy_signals),
            allocated.count { it.action == MaterialAction.BUY }.toString(),
            null,
            null,
            positiveColor,
        )
        Kpi(stringResource(Res.string.kpi_budget), formatIsk(budget), null, null, null)
        Kpi(stringResource(Res.string.kpi_to_buy), formatIsk(allocated.sumOf { it.toBuyIsk }), null, null, null, Res.string.mi_tip_to_buy)
        Kpi(
            stringResource(Res.string.kpi_held_pnl),
            if (held.isEmpty()) "—" else signed(held.sum()),
            null,
            null,
            null,
            valueColor = pnlColor(held.sum()),
            last = true,
        )
    }
}

package org.eventt.features.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.AlertDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PriceAlertModel
import org.eventt.features.market.liveZPrice

object AlertMonitor {
    private const val POLL_INTERVAL_MS = 5 * 60 * 1_000L
    private const val DEFAULT_REGION_ID = 10000002 // The Forge (Jita)

    // Newly triggered alerts waiting to be shown in the UI
    private val _triggered = MutableStateFlow<List<PriceAlertModel>>(emptyList())
    val triggered: StateFlow<List<PriceAlertModel>> = _triggered.asStateFlow()

    /** Best bid / best ask per (typeId, regionId) as of the last check — for "distance to target". */
    data class Quote(
        val bid: Double?,
        val ask: Double?,
    )

    private val _quotes = MutableStateFlow<Map<Pair<Int, Int>, Quote>>(emptyMap())
    val quotes: StateFlow<Map<Pair<Int, Int>, Quote>> = _quotes.asStateFlow()

    // Where each live z-score alert (PriceAlertModel.zTarget) would fire as of the last check, by alert id.
    private val _levels = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val levels: StateFlow<Map<Int, Double>> = _levels.asStateFlow()

    // (typeId, regionId, z) -> the price where the z-score equals z today; swapped out in tests.
    internal var zPrice: (Int, Int, Double) -> Double? = ::liveZPrice

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                checkAlerts()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun dismiss(alert: PriceAlertModel) {
        _triggered.value = _triggered.value.filter { it.id != alert.id }
    }

    fun dismissAll() {
        _triggered.value = emptyList()
    }

    /** Runs a check now (ESI responses are cached, so this is cheap between ticks). */
    suspend fun checkNow() = withContext(Dispatchers.IO) { checkAlerts() }

    fun effectiveRegionOf(alert: PriceAlertModel): Int = effectiveRegion(alert)

    internal suspend fun checkAlerts() {
        val alerts =
            try {
                AlertDao.getEnabled().filter { !it.triggered }
            } catch (_: Exception) {
                return
            }

        if (alerts.isEmpty()) return

        // Group by (typeId, regionId) to make one ESI call per unique pair
        val groups = alerts.groupBy { it.typeId to effectiveRegion(it) }
        val newlyTriggered = mutableListOf<PriceAlertModel>()
        val quotes = mutableMapOf<Pair<Int, Int>, Quote>()
        val levels = mutableMapOf<Int, Double>()

        for ((key, group) in groups) {
            val (typeId, regionId) = key
            val orders =
                runCatching {
                    EsiClient.getMarketRegionOrders(regionId, typeId = typeId)
                }.getOrDefault(emptyList())

            val bestSell =
                orders
                    .filter { (it["is_buy_order"] as? Boolean) == false }
                    .minOfOrNull { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
            val bestBuy =
                orders
                    .filter { (it["is_buy_order"] as? Boolean) == true }
                    .maxOfOrNull { (it["price"] as? Number)?.toDouble() ?: 0.0 }

            quotes[key] = Quote(bestBuy, bestSell)
            for (alert in group) {
                val current = if (alert.orderType == "buy") bestBuy else bestSell
                // A live z alert's level moves with the market and never drops under its stored
                // floor. No level (not enough history, ESI down) = nothing to compare with yet.
                val target =
                    if (alert.zTarget != null) {
                        val band = runCatching { zPrice(typeId, regionId, alert.zTarget!!) }.getOrNull() ?: continue
                        maxOf(band, alert.targetPrice).also { levels[alert.id] = it }
                    } else {
                        alert.targetPrice
                    }
                if (current == null) continue
                val fires =
                    when (alert.condition) {
                        "above" -> current >= target
                        "below" -> current <= target
                        else -> false
                    }
                if (fires) {
                    runCatching { AlertDao.markTriggered(alert.id) }
                    // The copy carries the level it fired at, for the triggered-alerts panel.
                    newlyTriggered.add(alert.copy(targetPrice = target, triggered = true, triggeredAt = System.currentTimeMillis()))
                }
            }
        }

        _quotes.value = _quotes.value + quotes
        _levels.value = _levels.value + levels
        if (newlyTriggered.isNotEmpty()) {
            _triggered.value = _triggered.value + newlyTriggered
        }
    }

    private fun effectiveRegion(alert: PriceAlertModel): Int = if (alert.regionId > 0) alert.regionId else DEFAULT_REGION_ID
}

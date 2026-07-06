package org.eventt.features.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eventt.core.database.AlertDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PriceAlertModel

object AlertMonitor {

    private const val POLL_INTERVAL_MS = 5 * 60 * 1_000L
    private const val DEFAULT_REGION_ID = 10000002 // The Forge (Jita)

    // Newly triggered alerts waiting to be shown in the UI
    private val _triggered = MutableStateFlow<List<PriceAlertModel>>(emptyList())
    val triggered: StateFlow<List<PriceAlertModel>> = _triggered.asStateFlow()

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

    private suspend fun checkAlerts() {
        val alerts = try {
            AlertDao.getEnabled().filter { !it.triggered }
        } catch (_: Exception) { return }

        if (alerts.isEmpty()) return

        // Group by (typeId, regionId) to make one ESI call per unique pair
        val groups = alerts.groupBy { it.typeId to effectiveRegion(it) }
        val newlyTriggered = mutableListOf<PriceAlertModel>()

        for ((key, group) in groups) {
            val (typeId, regionId) = key
            val orders = runCatching {
                EsiClient.getMarketRegionOrders(regionId, typeId = typeId)
            }.getOrDefault(emptyList())

            val bestSell = orders
                .filter { (it["is_buy_order"] as? Boolean) == false }
                .minOfOrNull { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE }
            val bestBuy = orders
                .filter { (it["is_buy_order"] as? Boolean) == true }
                .maxOfOrNull { (it["price"] as? Number)?.toDouble() ?: 0.0 }

            for (alert in group) {
                val current = if (alert.orderType == "buy") bestBuy else bestSell
                if (current == null) continue
                val fires = when (alert.condition) {
                    "above" -> current >= alert.targetPrice
                    "below" -> current <= alert.targetPrice
                    else    -> false
                }
                if (fires) {
                    runCatching { AlertDao.markTriggered(alert.id) }
                    newlyTriggered.add(alert.copy(triggered = true))
                }
            }
        }

        if (newlyTriggered.isNotEmpty()) {
            _triggered.value = _triggered.value + newlyTriggered
        }
    }

    private fun effectiveRegion(alert: PriceAlertModel): Int =
        if (alert.regionId > 0) alert.regionId else DEFAULT_REGION_ID
}

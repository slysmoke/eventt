package org.eventt.features.orders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eventt.core.database.ActiveOrderDao
import org.eventt.core.database.MarketTopSnapshotDao
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient

internal const val NOTIFY_BEATEN_SETTING = "orders.notify_beaten"

/**
 * App-lifetime market watcher (same start()/stop()-from-Main.kt shape as MarketLogWatcher): every
 * sweep it first refreshes EVERY character's and corp's own active-order list from ESI (see
 * [ActiveOrdersSyncService.syncAll] — not just whoever's on screen, so a character nobody has
 * opened the Orders tab for recently doesn't keep showing long-filled/cancelled orders forever,
 * which used to make both this sweep's own beaten-order detection and the OBS overlay's order
 * counts silently drift stale), then walks the now-fresh active orders to refresh each (type,
 * region) public order book, record competition snapshots, detect relists, and fire the
 * beaten-order tray notification. This is what keeps "you got undercut" working while you're on
 * another tab or switched to another character.
 *
 * Costs almost nothing between ESI ticks: EsiClient serves cached responses locally during their
 * cooldown (~25-30min for the per-character/corp order list, ~5min for public market books), so
 * only expired pairs actually hit the network. Detection is idempotent with the Orders screen's
 * own fetch (bumpRelistStats rejects duplicates, snapshots dedup by Last-Modified), so both can
 * look at the same response safely.
 */
object MarketWatchService {
    private const val SWEEP_INTERVAL_MILLIS = 2L * 60 * 1000

    private var scope: CoroutineScope? = null

    // Order ids observed beaten on the previous sweep — notifications fire only on the transition
    // into this set. Null until the first sweep seeds it: no notification burst at startup for
    // orders that were already beaten before the app opened.
    private var previouslyBeaten: Set<Long>? = null

    private val _beatenCount = MutableStateFlow(0)

    /** How many active orders (all characters/corps) are currently beaten — drives the nav badge. */
    val beatenCount: StateFlow<Int> = _beatenCount.asStateFlow()

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                runCatching { sweep() }
                delay(SWEEP_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        previouslyBeaten = null
    }

    private fun sweep() {
        ActiveOrdersSyncService.syncAll()
        val active = ActiveOrderDao.getAll().filter { it.state == "active" }
        if (active.isEmpty()) return

        val prev = previouslyBeaten
        val beatenNow = mutableSetOf<Long>()
        val freshlyBeatenNames = mutableListOf<String>()

        for ((key, orders) in active.groupBy { it.typeId to it.regionId }) {
            val (typeId, regionId) = key
            if (typeId <= 0 || regionId <= 0) continue
            val book =
                try {
                    EsiClient.getMarketRegionOrders(regionId, "all", typeId)
                } catch (_: Exception) {
                    continue
                }
            val asOf =
                EsiClient.getEndpointLastModifiedMillis(
                    "/markets/$regionId/orders/",
                    mapOf("order_type" to "all", "type_id" to typeId.toString()),
                )
            val ownIds = orders.map { it.orderId }.toSet()

            if (asOf != null) {
                CompetitionService.recordTopSnapshots(
                    book,
                    ownIds,
                    sellStationIds = orders.filter { !it.isBuyOrder }.map { it.locationId }.toSet(),
                    hasBuyOrders = orders.any { it.isBuyOrder },
                    typeId = typeId,
                    regionId = regionId,
                    ts = asOf,
                )
            }

            fun Map<String, Any?>.oid() = (get("order_id") as? Number)?.toLong()

            fun Map<String, Any?>.price() = (get("price") as? Number)?.toDouble()

            fun Map<String, Any?>.isBuy() = get("is_buy_order") as? Boolean ?: false

            fun Map<String, Any?>.loc() = (get("location_id") as? Number)?.toLong()

            val bookById = book.mapNotNull { m -> m.oid()?.let { it to m } }.toMap()
            for (order in orders) {
                val live = bookById[order.orderId] ?: continue
                val livePrice = live.price() ?: continue
                val liveVol = (live["volume_remain"] as? Number)?.toInt() ?: order.volumeRemaining

                // Relist detection — same staleness rule as the Orders screen; the DAO's own
                // guards make a double-detection (screen + this sweep) apply exactly once.
                val ownerChar = order.characterId
                if (ownerChar != null && livePrice != order.price && asOf != null && asOf >= order.priceUpdatedAt) {
                    val fee =
                        OrderFeeService.computeModificationFee(
                            order.price,
                            livePrice,
                            liveVol,
                            StaticDataDao.getCharBrokersFee(ownerChar),
                            OrderFeeService.relistDiscountPct(StaticDataDao.getCharRelistSkillLevel(ownerChar)),
                        )
                    ActiveOrderDao.bumpRelistStats(order.orderId, ownerChar, livePrice, fee, asOf)
                }

                // ponytail: buy-side beaten is region-wide, without the citadel jump-range
                // reachability the Orders screen applies — port isBuyOrderReachable here if
                // ranged-buy false positives ever get reported.
                val isBeaten =
                    if (order.isBuyOrder) {
                        book.any { it.isBuy() && (it.oid() ?: -1L) !in ownIds && (it.price() ?: 0.0) > livePrice }
                    } else {
                        book.any {
                            !it.isBuy() &&
                                (it.oid() ?: -1L) !in ownIds &&
                                it.loc() == order.locationId &&
                                (it.price() ?: Double.MAX_VALUE) < livePrice
                        }
                    }
                if (isBeaten) {
                    beatenNow += order.orderId
                    if (prev != null && order.orderId !in prev) freshlyBeatenNames += order.typeName
                }
            }
        }

        MarketTopSnapshotDao.pruneOlderThan(System.currentTimeMillis() - CompetitionService.WINDOW_MILLIS)
        previouslyBeaten = beatenNow
        _beatenCount.value = beatenNow.size

        if (freshlyBeatenNames.isNotEmpty() && StaticDataDao.getSetting(NOTIFY_BEATEN_SETTING) != "false") {
            val names = freshlyBeatenNames.take(3).joinToString(", ")
            val suffix = if (freshlyBeatenNames.size > 3) " (+${freshlyBeatenNames.size - 3} more)" else ""
            PendingOrdersQueue.notifier?.invoke("Orders beaten", names + suffix)
        }
    }
}

package org.eventt.core.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderDao
import org.eventt.core.database.NostrOrderModel

data class OrderFilter(
    val typeId: Int? = null,
    val regionId: Int? = null,
    val side: OrderSide? = null,
)

/** Read/write facade over P2P Market orders — screens go through this, never the DAO directly. */
object OrderRepository {
    /**
     * Emits the current local DB snapshot immediately (so Browse has something to show before
     * any relay round-trip completes), then re-emits every time [NostrRelayManager] persists a
     * newer order revision — collectors don't poll, they just react to the shared relay-event flow.
     */
    fun browse(filter: OrderFilter = OrderFilter()): Flow<List<NostrOrderModel>> =
        flow {
            suspend fun query() =
                withContext(Dispatchers.IO) {
                    NostrOrderDao.queryActive(filter.typeId, filter.regionId, filter.side?.name?.lowercase())
                }
            emit(query())
            NostrRelayManager.events.collect { emit(query()) }
        }
}

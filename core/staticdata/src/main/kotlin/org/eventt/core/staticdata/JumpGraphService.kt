package org.eventt.core.staticdata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds and caches the stargate connectivity graph for a region, so station-trading analysis
 * can determine whether a buy order elsewhere in the region — a different station, a citadel, a
 * neighboring system — is actually within its stated order `range` of the station being analyzed.
 *
 * The graph is scoped to a single region: EVE's per-region market-orders endpoint can't return
 * orders from other regions anyway, so a shortest path that briefly left the region wouldn't be
 * discoverable through the order data either. Each system's stargates are fetched from ESI once
 * ever and persisted locally afterward, since stargate topology never changes.
 */
object JumpGraphService {

    data class Progress(val fetched: Int, val total: Int)

    /**
     * Ensures every system in [regionId] has its stargate edges cached locally, fetching any
     * missing ones from ESI (public endpoints, no auth needed). Safe to call at the start of
     * every analysis run — regions fetched in a previous run return almost immediately.
     */
    suspend fun ensureRegionGraph(regionId: Int, onProgress: (Progress) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val systemIds = StaticDataDao.getSystemIdsByRegion(regionId)
            val missing = systemIds.filterNot { StaticDataDao.isSystemJumpsFetched(it) }
            if (missing.isEmpty()) return@withContext

            val fetched = AtomicInteger(0)
            val semaphore = Semaphore(10)
            coroutineScope {
                missing.map { systemId ->
                    async {
                        semaphore.withPermit {
                            runCatching { fetchSystemEdges(systemId) }
                            onProgress(Progress(fetched.incrementAndGet(), missing.size))
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun fetchSystemEdges(systemId: Int) {
        val system = EsiClient.getUniverseSystem(systemId)
        val stargateIds = (system["stargates"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val edges = stargateIds.mapNotNull { gateId ->
            runCatching {
                val gate = EsiClient.getUniverseStargate(gateId)
                val destSystemId = ((gate["destination"] as? Map<*, *>)?.get("system_id") as? Number)?.toInt()
                destSystemId?.let { systemId to it }
            }.getOrNull()
        }
        if (edges.isNotEmpty()) StaticDataDao.insertSystemJumpEdges(edges)
        StaticDataDao.markSystemJumpsFetched(systemId)
    }

    /**
     * BFS distances (in jumps) from [fromSystemId] to every system reachable within [regionId]'s
     * own cached subgraph. Call [ensureRegionGraph] first so the data actually exists.
     */
    fun bfsDistances(fromSystemId: Int, regionId: Int): Map<Int, Int> {
        val systemIds = StaticDataDao.getSystemIdsByRegion(regionId)
        val graph = StaticDataDao.getJumpGraph(systemIds)
        val distances = mutableMapOf(fromSystemId to 0)
        val queue = ArrayDeque<Int>()
        queue.addLast(fromSystemId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val dist = distances.getValue(current)
            for (neighbor in graph[current].orEmpty()) {
                if (neighbor !in distances) {
                    distances[neighbor] = dist + 1
                    queue.addLast(neighbor)
                }
            }
        }
        return distances
    }
}

package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.model.WatchlistEntryModel
import org.eventt.core.model.WatchlistPriceSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class WatchlistDaoTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM watchlist")
                it.execute("DELETE FROM watchlist_prices")
            }
        }
    }

    private fun entry(
        typeId: Int,
        watchlistName: String = "Default",
        sortOrder: Int = 0,
    ) = WatchlistEntryModel(typeId = typeId, typeName = "Type $typeId", watchlistName = watchlistName, sortOrder = sortOrder)

    @Test
    fun `insert generates an id and getByWatchlistName reads it back`() {
        val id = WatchlistDao.insert(entry(34))

        (id > 0) shouldBe true
        WatchlistDao.getByWatchlistName("Default").single().typeId shouldBe 34
    }

    @Test
    fun `getByWatchlistName orders entries by sortOrder`() {
        WatchlistDao.insert(entry(1, sortOrder = 2))
        WatchlistDao.insert(entry(2, sortOrder = 0))
        WatchlistDao.insert(entry(3, sortOrder = 1))

        WatchlistDao.getByWatchlistName("Default").map { it.typeId } shouldBe listOf(2, 3, 1)
    }

    @Test
    fun `getAllWatchlists groups entries by watchlist name`() {
        WatchlistDao.insert(entry(1, watchlistName = "Minerals"))
        WatchlistDao.insert(entry(2, watchlistName = "Ships"))
        WatchlistDao.insert(entry(3, watchlistName = "Minerals"))

        val all = WatchlistDao.getAllWatchlists()

        all.keys shouldBe setOf("Minerals", "Ships")
        all.getValue("Minerals").map { it.typeId }.toSet() shouldBe setOf(1, 3)
    }

    @Test
    fun `delete removes only the targeted entry`() {
        val id1 = WatchlistDao.insert(entry(1))
        WatchlistDao.insert(entry(2))

        WatchlistDao.delete(id1)

        WatchlistDao.getByWatchlistName("Default").map { it.typeId } shouldBe listOf(2)
    }

    @Test
    fun `getByWatchlistName for an unknown list is empty`() {
        WatchlistDao.getByWatchlistName("Nonexistent").shouldBeEmpty()
    }

    private fun snapshot(
        typeId: Int,
        stationId: Long = 60003760L,
        bestBuy: Double = 4.5,
        sparkline: List<Pair<String, Double>> = emptyList(),
    ) = WatchlistPriceSnapshot(
        typeId = typeId,
        stationId = stationId,
        bestBuyPrice = bestBuy,
        bestSellPrice = 5.0,
        sparklineData = sparkline,
    )

    @Test
    fun `insertPriceSnapshot then getLatestPrice returns the most recently captured snapshot`() {
        WatchlistDao.insertPriceSnapshot(snapshot(34, bestBuy = 4.0))
        // captured_at is second-granularity (strftime('%s','now')); force the two rows into
        // different seconds so "most recent" has an unambiguous answer.
        Thread.sleep(1100)
        WatchlistDao.insertPriceSnapshot(snapshot(34, bestBuy = 4.8))

        val latest = WatchlistDao.getLatestPrice(34).shouldNotBeNull()

        latest.bestBuyPrice should (4.8 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getLatestPrice for a type with no snapshots is null`() {
        WatchlistDao.getLatestPrice(999).shouldBeNull()
    }

    @Test
    fun `getPriceHistory scopes by both typeId and stationId`() {
        WatchlistDao.insertPriceSnapshot(snapshot(34, stationId = 60003760L))
        WatchlistDao.insertPriceSnapshot(snapshot(34, stationId = 60008494L))

        WatchlistDao.getPriceHistory(34, stationId = 60003760L).size shouldBe 1
    }

    @Test
    fun `getLatestPrice round-trips sparkline data`() {
        WatchlistDao.insertPriceSnapshot(snapshot(34, sparkline = listOf("2024-01-01" to 4.5, "2024-01-02" to 4.7)))

        val latest = WatchlistDao.getLatestPrice(34).shouldNotBeNull()

        latest.sparklineData shouldBe listOf("2024-01-01" to 4.5, "2024-01-02" to 4.7)
    }
}

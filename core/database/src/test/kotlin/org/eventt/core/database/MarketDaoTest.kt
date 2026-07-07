package org.eventt.core.database

import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class MarketDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM market_history") } }
    }

    private fun entry(date: String) =
        MarketHistoryModel(
            typeId = 34,
            regionId = 10000002,
            date = date,
            average = 5.5,
            volume = 1000L,
            orderCount = 20L,
            highest = 6.0,
            lowest = 5.0,
        )

    @Test
    fun `insertHistory defaults to source esi and is readable via getHistory`() {
        MarketDao.insertHistory(entry("2024-01-02"))

        MarketDao.getHistory(34, 10000002).single().date shouldBe "2024-01-02"
    }

    @Test
    fun `getHistory returns rows newest first`() {
        MarketDao.insertHistory(entry("2024-01-01"))
        MarketDao.insertHistory(entry("2024-01-03"))
        MarketDao.insertHistory(entry("2024-01-02"))

        MarketDao.getHistory(34, 10000002).map { it.date } shouldBe listOf("2024-01-03", "2024-01-02", "2024-01-01")
    }

    @Test
    fun `getHistoryBySource filters by source, leaving other sources' rows out`() {
        MarketDao.insertHistory(entry("2024-01-02")) // source defaults to esi
        MarketDao.insertHistoryBatch(listOf(entry("2024-01-01"), entry("2024-01-03")), source = "everef")

        MarketDao.getHistoryBySource(34, 10000002, source = "everef").map { it.date } shouldBe
            listOf("2024-01-03", "2024-01-01")
        MarketDao.getHistoryBySource(34, 10000002, source = "esi").map { it.date } shouldBe listOf("2024-01-02")
    }

    @Test
    fun `deleteEveRefBeforeDate only removes everef rows older than the cutoff`() {
        MarketDao.insertHistory(entry("2024-01-02")) // esi, unaffected regardless of date
        MarketDao.insertHistoryBatch(listOf(entry("2024-01-01")), source = "everef") // before cutoff, deleted
        MarketDao.insertHistoryBatch(listOf(entry("2024-01-05")), source = "everef") // on/after cutoff, kept

        MarketDao.deleteEveRefBeforeDate("2024-01-02")

        MarketDao.getHistory(34, 10000002).map { it.date }.toSet() shouldBe setOf("2024-01-02", "2024-01-05")
    }

    @Test
    fun `getHistory respects the days limit`() {
        MarketDao.insertHistory(entry("2024-01-01"))
        MarketDao.insertHistory(entry("2024-01-02"))
        MarketDao.insertHistory(entry("2024-01-03"))

        MarketDao.getHistory(34, 10000002, days = 2).map { it.date } shouldBe listOf("2024-01-03", "2024-01-02")
    }
}

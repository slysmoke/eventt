package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class OrderHistoryDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM order_history") } }
    }

    private fun record(
        orderId: Long,
        isBuyOrder: Boolean = false,
        issued: String = "2024-01-01",
        characterId: Int? = 1,
    ) = OrderHistoryDao.OrderHistoryRecord(
        orderId = orderId,
        typeId = 34,
        typeName = "Tritanium",
        locationId = 60003760L,
        stationName = "Jita IV - Moon 4",
        price = 5.0,
        volumeTotal = 1000,
        volumeRemaining = 0,
        isBuyOrder = isBuyOrder,
        duration = 90,
        issued = issued,
        range = "station",
        minVolume = 1,
        state = "expired",
        characterId = characterId,
    )

    @Test
    fun `upsertAll on an empty list does nothing`() {
        OrderHistoryDao.upsertAll(emptyList())

        OrderHistoryDao.getAll(characterId = 1).shouldBeEmpty()
    }

    @Test
    fun `upsertAll inserts every record and getAll reads them back newest-issued-first`() {
        OrderHistoryDao.upsertAll(
            listOf(
                record(1, issued = "2024-01-01"),
                record(2, issued = "2024-01-03"),
                record(3, issued = "2024-01-02"),
            ),
        )

        OrderHistoryDao.getAll(characterId = 1).map { it.orderId } shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `upsertAll replaces an existing row with the same order id`() {
        OrderHistoryDao.upsertAll(listOf(record(1, issued = "2024-01-01")))
        OrderHistoryDao.upsertAll(listOf(record(1, issued = "2024-01-01").copy(state = "cancelled")))

        OrderHistoryDao.getAll(characterId = 1).single().state shouldBe "cancelled"
    }

    @Test
    fun `getAll filters by isBuyOrder when specified`() {
        OrderHistoryDao.upsertAll(listOf(record(1, isBuyOrder = true), record(2, isBuyOrder = false)))

        OrderHistoryDao.getAll(characterId = 1, isBuyOrder = true).map { it.orderId } shouldBe listOf(1L)
        OrderHistoryDao.getAll(characterId = 1, isBuyOrder = false).map { it.orderId } shouldBe listOf(2L)
    }

    @Test
    fun `getAll respects the limit`() {
        OrderHistoryDao.upsertAll(listOf(record(1), record(2), record(3)))

        OrderHistoryDao.getAll(characterId = 1, limit = 2).size shouldBe 2
    }

    @Test
    fun `getAll only returns rows for the requested character`() {
        OrderHistoryDao.upsertAll(listOf(record(1, characterId = 1), record(2, characterId = 2)))

        OrderHistoryDao.getAll(characterId = 1).map { it.orderId } shouldBe listOf(1L)
    }
}

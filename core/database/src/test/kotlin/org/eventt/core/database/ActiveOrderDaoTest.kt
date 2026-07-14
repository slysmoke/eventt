package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ActiveOrderDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM active_orders") } }
    }

    private fun record(
        orderId: Long,
        price: Double = 5.0,
        characterId: Int? = 1,
        corporationId: Int? = null,
        issuedByCharId: Int? = null,
        relistCount: Int = 0,
        relistFeesPaid: Double = 0.0,
    ) = ActiveOrderDao.ActiveOrderRecord(
        orderId = orderId,
        typeId = 34,
        typeName = "Tritanium",
        locationId = 60003760L,
        regionId = 10000002,
        stationName = "Jita IV - Moon 4",
        price = price,
        volumeTotal = 1000,
        volumeRemaining = 500,
        isBuyOrder = false,
        duration = 90,
        issued = "2024-01-01T00:00:00Z",
        state = "active",
        issuedByCharId = issuedByCharId,
        characterId = characterId,
        corporationId = corporationId,
        relistCount = relistCount,
        relistFeesPaid = relistFeesPaid,
    )

    @Test
    fun `replaceAll with no character or corp scope is a no-op`() {
        ActiveOrderDao.replaceAll(characterId = null, corporationId = null, records = listOf(record(1)))
        ActiveOrderDao.getAll(characterId = 1).shouldBeEmpty()
    }

    @Test
    fun `replaceAll inserts records and getAll reads them back scoped to the character`() {
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(1), record(2)))

        ActiveOrderDao.getAll(characterId = 1).map { it.orderId }.toSet() shouldBe setOf(1L, 2L)
        ActiveOrderDao.getAll(characterId = 99).shouldBeEmpty()
    }

    @Test
    fun `replaceAll wholesale-replaces the scope's snapshot — a vanished order is not left behind`() {
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(1), record(2)))

        // Order 1 got filled/cancelled and is no longer in the fresh snapshot — only order 2 remains.
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(2, price = 6.0)))

        val result = ActiveOrderDao.getAll(characterId = 1)
        result.size shouldBe 1
        result.single().orderId shouldBe 2L
        result.single().price shouldBe 6.0
    }

    @Test
    fun `replaceAll with an empty list clears the scope's snapshot`() {
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(1)))
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = emptyList())

        ActiveOrderDao.getAll(characterId = 1).shouldBeEmpty()
    }

    @Test
    fun `replaceAll for a character does not disturb a different character's or corp's snapshot`() {
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(1, characterId = 1)))
        ActiveOrderDao.replaceAll(characterId = 2, corporationId = null, records = listOf(record(2, characterId = 2)))
        ActiveOrderDao.replaceAll(
            characterId = null,
            corporationId = 500,
            records = listOf(record(3, characterId = null, corporationId = 500)),
        )

        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = emptyList())

        ActiveOrderDao.getAll(characterId = 1).shouldBeEmpty()
        ActiveOrderDao.getAll(characterId = 2).size shouldBe 1
        ActiveOrderDao.getAll(corporationId = 500).size shouldBe 1
    }

    @Test
    fun `getAll preserves issuedByCharId for corp-scoped orders`() {
        ActiveOrderDao.replaceAll(
            characterId = null,
            corporationId = 500,
            records = listOf(record(1, characterId = null, corporationId = 500, issuedByCharId = 42)),
        )

        ActiveOrderDao.getAll(corporationId = 500).single().issuedByCharId shouldBe 42
    }

    @Test
    fun `replaceAll round-trips relistCount and relistFeesPaid`() {
        ActiveOrderDao.replaceAll(
            characterId = 1,
            corporationId = null,
            records = listOf(record(1, relistCount = 3, relistFeesPaid = 450.0)),
        )

        val stored = ActiveOrderDao.getAll(characterId = 1).single()
        stored.relistCount shouldBe 3
        stored.relistFeesPaid shouldBe 450.0
    }

    @Test
    fun `bumpRelistStats increments count and fees and updates price for the matching order only`() {
        ActiveOrderDao.replaceAll(
            characterId = 1,
            corporationId = null,
            records = listOf(record(1, price = 100.0), record(2, price = 200.0)),
        )

        ActiveOrderDao.bumpRelistStats(orderId = 1, characterId = 1, corporationId = null, newPrice = 110.0, addedFee = 100.0)
        ActiveOrderDao.bumpRelistStats(orderId = 1, characterId = 1, corporationId = null, newPrice = 120.0, addedFee = 100.0)

        val orders = ActiveOrderDao.getAll(characterId = 1).associateBy { it.orderId }
        orders.getValue(1).price shouldBe 120.0
        orders.getValue(1).relistCount shouldBe 2
        orders.getValue(1).relistFeesPaid shouldBe 200.0
        // Untouched order keeps its original price and zero relist stats.
        orders.getValue(2).price shouldBe 200.0
        orders.getValue(2).relistCount shouldBe 0
    }

    @Test
    fun `bumpRelistStats is scoped and does not affect a different character's order`() {
        ActiveOrderDao.replaceAll(characterId = 1, corporationId = null, records = listOf(record(1, characterId = 1)))
        ActiveOrderDao.replaceAll(characterId = 2, corporationId = null, records = listOf(record(2, characterId = 2)))

        ActiveOrderDao.bumpRelistStats(orderId = 1, characterId = 1, corporationId = null, newPrice = 999.0, addedFee = 50.0)

        ActiveOrderDao.getAll(characterId = 1).single().relistCount shouldBe 1
        ActiveOrderDao.getAll(characterId = 2).single().relistCount shouldBe 0
    }
}

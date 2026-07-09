package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NostrOrderDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM nostr_orders") } }
    }

    private fun order(
        orderUuid: String = "order-1",
        pubkey: String = "seller-pubkey",
        createdAt: Long = 1_000L,
        qtyRemaining: Long = 10_000L,
        eventId: String = "event-$createdAt",
    ) = NostrOrderModel(
        orderUuid = orderUuid,
        pubkey = pubkey,
        eventId = eventId,
        createdAt = createdAt,
        side = "sell",
        typeId = 44992,
        regionId = 10000002,
        price = 480.5,
        qtyTotal = 10_000L,
        qtyRemaining = qtyRemaining,
        minLot = 500L,
        minLotUnit = "units",
        traderChar = "Some Character",
        expiration = System.currentTimeMillis() / 1000 + 14L * 24 * 3600,
        rawEventJson = "{}",
        isMine = true,
    )

    @Test
    fun `upsertIfNewer inserts a brand new coordinate`() {
        val inserted = NostrOrderDao.upsertIfNewer(order())

        inserted shouldBe true
        NostrOrderDao.getByCoordinate("order-1", "seller-pubkey").shouldNotBeNull()
    }

    @Test
    fun `upsertIfNewer replaces the row when created_at is strictly newer`() {
        NostrOrderDao.upsertIfNewer(order(createdAt = 1_000L, qtyRemaining = 10_000L))

        val replaced = NostrOrderDao.upsertIfNewer(order(createdAt = 2_000L, qtyRemaining = 9_000L))

        replaced shouldBe true
        NostrOrderDao.getByCoordinate("order-1", "seller-pubkey")?.qtyRemaining shouldBe 9_000L
    }

    @Test
    fun `upsertIfNewer is a no-op when created_at is older than the stored revision`() {
        NostrOrderDao.upsertIfNewer(order(createdAt = 2_000L, qtyRemaining = 9_000L))

        val replaced = NostrOrderDao.upsertIfNewer(order(createdAt = 1_000L, qtyRemaining = 10_000L))

        replaced shouldBe false
        NostrOrderDao.getByCoordinate("order-1", "seller-pubkey")?.qtyRemaining shouldBe 9_000L
    }

    @Test
    fun `upsertIfNewer is a no-op when created_at is equal to the stored revision`() {
        NostrOrderDao.upsertIfNewer(order(createdAt = 2_000L, qtyRemaining = 9_000L, eventId = "event-first"))

        val replaced = NostrOrderDao.upsertIfNewer(order(createdAt = 2_000L, qtyRemaining = 1L, eventId = "event-replay"))

        replaced shouldBe false
        NostrOrderDao.getByCoordinate("order-1", "seller-pubkey")?.eventId shouldBe "event-first"
    }

    @Test
    fun `queryActive excludes expired orders`() {
        NostrOrderDao.upsertIfNewer(order().copy(expiration = System.currentTimeMillis() / 1000 - 60))

        NostrOrderDao.queryActive().shouldBeEmpty()
    }

    @Test
    fun `getByCoordinate returns null for an unknown coordinate`() {
        NostrOrderDao.getByCoordinate("nope", "nope").shouldBeNull()
    }
}

package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.model.TrackedOrderModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class TrackedOrderDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM tracked_orders") } }
    }

    private fun order(
        characterId: Int? = 1,
        corporationId: Int? = null,
    ) = TrackedOrderModel(
        typeId = 34,
        typeName = "Tritanium",
        buyPrice = 5.0,
        quantity = 1000,
        characterId = characterId,
        corporationId = corporationId,
    )

    @Test
    fun `insert generates an id and getById reads it back`() {
        val id = TrackedOrderDao.insert(order())

        (id > 0) shouldBe true
        TrackedOrderDao.getById(id).shouldNotBeNull().typeName shouldBe "Tritanium"
    }

    @Test
    fun `insert assigns increasing ids across multiple rows`() {
        val id1 = TrackedOrderDao.insert(order())
        val id2 = TrackedOrderDao.insert(order())

        (id2 > id1) shouldBe true
    }

    @Test
    fun `update changes the stored fields for that id`() {
        val id = TrackedOrderDao.insert(order())

        TrackedOrderDao.update(TrackedOrderDao.getById(id)!!.copy(notes = "watching this one", quantity = 2000))

        val updated = TrackedOrderDao.getById(id).shouldNotBeNull()
        updated.notes shouldBe "watching this one"
        updated.quantity shouldBe 2000
    }

    @Test
    fun `updateSellPrice only touches the sell price`() {
        val id = TrackedOrderDao.insert(order())

        TrackedOrderDao.updateSellPrice(id, 7.5)

        TrackedOrderDao.getById(id)?.currentSellPrice should (7.5 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getByCharacter and getByCorporation each return only their own orders`() {
        TrackedOrderDao.insert(order(characterId = 1, corporationId = null))
        TrackedOrderDao.insert(order(characterId = null, corporationId = 500))

        TrackedOrderDao
            .getByCharacter(1)
            .single()
            .corporationId
            .shouldBeNull()
        TrackedOrderDao
            .getByCorporation(500)
            .single()
            .characterId
            .shouldBeNull()
    }

    @Test
    fun `getAll returns every tracked order regardless of owner`() {
        TrackedOrderDao.insert(order(characterId = 1))
        TrackedOrderDao.insert(order(characterId = null, corporationId = 500))

        TrackedOrderDao.getAll().size shouldBe 2
    }

    @Test
    fun `delete removes the order`() {
        val id = TrackedOrderDao.insert(order())

        TrackedOrderDao.delete(id)

        TrackedOrderDao.getById(id).shouldBeNull()
        TrackedOrderDao.getAll().shouldBeEmpty()
    }
}

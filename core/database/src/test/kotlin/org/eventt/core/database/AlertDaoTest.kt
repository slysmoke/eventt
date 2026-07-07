package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.model.PriceAlertModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AlertDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM price_alerts") } }
    }

    private fun alert(
        typeId: Int = 34,
        enabled: Boolean = true,
    ) = PriceAlertModel(typeId = typeId, targetPrice = 5.0, condition = "above", enabled = enabled)

    @Test
    fun `insert generates an id and getAll reads it back`() {
        val id = AlertDao.insert(alert())

        (id > 0) shouldBe true
        AlertDao.getAll().single().typeId shouldBe 34
    }

    @Test
    fun `getEnabled excludes disabled alerts`() {
        AlertDao.insert(alert(typeId = 1, enabled = true))
        AlertDao.insert(alert(typeId = 2, enabled = false))

        AlertDao.getEnabled().map { it.typeId } shouldBe listOf(1)
    }

    @Test
    fun `update changes the stored fields for that id`() {
        val id = AlertDao.insert(alert())

        AlertDao.update(AlertDao.getAll().single().copy(targetPrice = 9.99))

        AlertDao.getAll().single { it.id == id }.targetPrice shouldBe 9.99
    }

    @Test
    fun `setEnabled toggles the enabled flag without touching other fields`() {
        val id = AlertDao.insert(alert(enabled = true))

        AlertDao.setEnabled(id, false)

        AlertDao.getAll().single().enabled shouldBe false
        AlertDao.getEnabled().shouldBeEmpty()
    }

    @Test
    fun `markTriggered sets triggered and triggeredAt`() {
        val id = AlertDao.insert(alert())

        AlertDao.markTriggered(id)

        val loaded = AlertDao.getAll().single()
        loaded.triggered shouldBe true
        loaded.triggeredAt.shouldNotBeNull()
    }

    @Test
    fun `a freshly inserted alert has not been triggered`() {
        AlertDao.insert(alert())

        val loaded = AlertDao.getAll().single()
        loaded.triggered shouldBe false
        loaded.triggeredAt.shouldBeNull()
    }

    @Test
    fun `delete removes the alert`() {
        val id = AlertDao.insert(alert())

        AlertDao.delete(id)

        AlertDao.getAll().shouldBeEmpty()
    }
}

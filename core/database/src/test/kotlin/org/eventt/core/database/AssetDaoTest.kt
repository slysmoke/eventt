package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.model.AssetModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class AssetDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM assets") } }
    }

    private fun asset(
        itemId: Long,
        characterId: Int? = 1,
        corporationId: Int? = null,
        quantity: Int = 10,
        estimatedPrice: Double = 100.0,
    ) = AssetModel(
        itemId = itemId,
        typeId = 34,
        typeName = "Tritanium",
        quantity = quantity,
        locationId = 60003760L,
        locationName = "Jita IV - Moon 4",
        estimatedPrice = estimatedPrice,
        characterId = characterId,
        corporationId = corporationId,
    )

    @Test
    fun `upsert then getByCharacter round-trips an asset`() {
        AssetDao.upsert(asset(itemId = 1))

        val assets = AssetDao.getByCharacter(1)

        assets.single().typeName shouldBe "Tritanium"
    }

    @Test
    fun `upsert replaces an existing row with the same item id`() {
        AssetDao.upsert(asset(itemId = 1, quantity = 10))
        AssetDao.upsert(asset(itemId = 1, quantity = 25))

        AssetDao.getByCharacter(1).single().quantity shouldBe 25
    }

    @Test
    fun `replaceFor inserts every asset in one call`() {
        AssetDao.replaceFor(characterId = 1, assets = listOf(asset(itemId = 1), asset(itemId = 2), asset(itemId = 3)))

        AssetDao.getByCharacter(1) shouldHaveSize 3
    }

    @Test
    fun `replaceFor drops assets missing from the new snapshot`() {
        AssetDao.replaceFor(characterId = 1, assets = listOf(asset(itemId = 1), asset(itemId = 2)))

        // Item 1 was sold/moved in game — the fresh ESI snapshot no longer contains it.
        AssetDao.replaceFor(characterId = 1, assets = listOf(asset(itemId = 2)))

        AssetDao.getByCharacter(1).map { it.itemId } shouldBe listOf(2L)
    }

    @Test
    fun `replaceFor only touches the owner being replaced`() {
        AssetDao.upsert(asset(itemId = 1, characterId = 2))
        AssetDao.replaceFor(characterId = 1, assets = listOf(asset(itemId = 2)))

        AssetDao.getByCharacter(2) shouldHaveSize 1
        AssetDao.getByCharacter(1) shouldHaveSize 1
    }

    @Test
    fun `getByCorporation only returns corp-owned assets, not character-owned ones`() {
        AssetDao.upsert(asset(itemId = 1, characterId = 1, corporationId = null))
        AssetDao.upsert(asset(itemId = 2, characterId = null, corporationId = 500))

        AssetDao.getByCorporation(500).single().itemId shouldBe 2L
        AssetDao.getByCharacter(1).single().itemId shouldBe 1L
    }

    @Test
    fun `getTotalValue sums estimatedPrice times quantity across all matching rows`() {
        AssetDao.upsert(asset(itemId = 1, quantity = 10, estimatedPrice = 5.0)) // 50
        AssetDao.upsert(asset(itemId = 2, quantity = 3, estimatedPrice = 100.0)) // 300

        AssetDao.getTotalValue(characterId = 1) should (350.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `getTotalValue is zero when there are no matching assets`() {
        AssetDao.getTotalValue(characterId = 999) should (0.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `deleteByCharacter removes only that character's assets`() {
        AssetDao.upsert(asset(itemId = 1, characterId = 1))
        AssetDao.upsert(asset(itemId = 2, characterId = 2))

        AssetDao.deleteByCharacter(1)

        AssetDao.getByCharacter(1).shouldBeEmpty()
        AssetDao.getByCharacter(2) shouldHaveSize 1
    }

    @Test
    fun `deleteByCorporation removes only that corporation's assets`() {
        AssetDao.upsert(asset(itemId = 1, characterId = null, corporationId = 500))
        AssetDao.upsert(asset(itemId = 2, characterId = null, corporationId = 600))

        AssetDao.deleteByCorporation(500)

        AssetDao.getByCorporation(500).shouldBeEmpty()
        AssetDao.getByCorporation(600) shouldHaveSize 1
    }
}

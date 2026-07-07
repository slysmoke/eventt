package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.eventt.core.model.StaticGroupModel
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticStationModel
import org.eventt.core.model.StaticSystemModel
import org.eventt.core.model.StaticTypeModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import io.kotest.matchers.maps.shouldBeEmpty as shouldBeEmptyMap

private const val TOLERANCE = 0.0001

class StaticDataDaoTest {
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
            createStatement().use { stmt ->
                listOf(
                    "static_types",
                    "static_groups",
                    "static_categories",
                    "market_groups",
                    "static_stations",
                    "static_regions",
                    "static_systems",
                    "static_system_jumps",
                    "static_system_jumps_fetched",
                    "settings",
                ).forEach { stmt.execute("DELETE FROM $it") }
            }
        }
    }

    private fun type(
        typeId: Int,
        name: String,
        groupId: Int = 1,
        published: Boolean = true,
        marketGroupId: Int? = null,
    ) = StaticTypeModel(
        typeId = typeId,
        name = name,
        groupId = groupId,
        categoryId = 1,
        published = published,
        marketGroupId = marketGroupId,
    )

    // ─── Types ──────────────────────────────────────────────────────────────

    @Test
    fun `bulkInsertTypes then getTypeById and getTypeName round-trip`() {
        StaticDataDao.bulkInsertTypes(listOf(type(34, "Tritanium")))

        StaticDataDao.getTypeById(34).shouldNotBeNull().name shouldBe "Tritanium"
        StaticDataDao.getTypeName(34) shouldBe "Tritanium"
        StaticDataDao.getTypeName(999).shouldBeNull()
    }

    @Test
    fun `countTypes reflects the number of inserted types`() {
        StaticDataDao.bulkInsertTypes(listOf(type(1, "A"), type(2, "B"), type(3, "C")))

        StaticDataDao.countTypes() shouldBe 3
    }

    @Test
    fun `searchTypes ranks an exact match first, then prefix matches, then substring matches`() {
        StaticDataDao.bulkInsertTypes(
            listOf(
                type(1, "Tritanium Ore"),
                type(2, "Tritanium"),
                type(3, "Rich Tritanium"),
            ),
        )

        StaticDataDao.searchTypes("Tritanium").map { it.name } shouldBe
            listOf("Tritanium", "Tritanium Ore", "Rich Tritanium")
    }

    @Test
    fun `searchTypes only returns published types`() {
        StaticDataDao.bulkInsertTypes(listOf(type(1, "Published Thing", published = true), type(2, "Unpublished Thing", published = false)))

        StaticDataDao.searchTypes("Thing").map { it.name } shouldBe listOf("Published Thing")
    }

    @Test
    fun `searchMarketTypes excludes types with no market group`() {
        StaticDataDao.bulkInsertTypes(listOf(type(1, "Tradeable", marketGroupId = 10), type(2, "Not Tradeable", marketGroupId = null)))

        StaticDataDao.searchMarketTypes("Trad").map { it.name } shouldBe listOf("Tradeable")
    }

    @Test
    fun `getTypesByGroup filters by group and published, ordered by name`() {
        StaticDataDao.bulkInsertTypes(
            listOf(
                type(1, "Zeta", groupId = 5),
                type(2, "Alpha", groupId = 5),
                type(3, "Beta", groupId = 6),
                type(4, "Unpublished", groupId = 5, published = false),
            ),
        )

        StaticDataDao.getTypesByGroup(5).map { it.name } shouldBe listOf("Alpha", "Zeta")
    }

    // ─── Groups ─────────────────────────────────────────────────────────────

    @Test
    fun `bulkInsertGroups then getGroupById round-trips`() {
        StaticDataDao.bulkInsertGroups(listOf(StaticGroupModel(groupId = 5, name = "Minerals", categoryId = 1)))

        StaticDataDao.getGroupById(5).shouldNotBeNull().name shouldBe "Minerals"
    }

    @Test
    fun `getGroupNameForType joins through the type's groupId`() {
        StaticDataDao.bulkInsertGroups(listOf(StaticGroupModel(groupId = 5, name = "Minerals", categoryId = 1)))
        StaticDataDao.bulkInsertTypes(listOf(type(34, "Tritanium", groupId = 5)))

        StaticDataDao.getGroupNameForType(34) shouldBe "Minerals"
    }

    // ─── Market groups ──────────────────────────────────────────────────────

    @Test
    fun `getTopMarketGroups returns only groups with no parent`() {
        StaticDataDao.bulkInsertMarketGroups(
            listOf(
                StaticMarketGroupModel(marketGroupId = 1, name = "Minerals", parentGroupId = null),
                StaticMarketGroupModel(marketGroupId = 2, name = "Ore", parentGroupId = 1),
            ),
        )

        StaticDataDao.getTopMarketGroups().map { it.marketGroupId } shouldBe listOf(1)
        StaticDataDao.getChildMarketGroups(1).map { it.marketGroupId } shouldBe listOf(2)
    }

    @Test
    fun `getMarketGroupById returns null for an unknown group`() {
        StaticDataDao.getMarketGroupById(999).shouldBeNull()
    }

    @Test
    fun `getTypesByMarketGroup and getTypeIdsByMarketGroups and getAllMarketTypeIds agree`() {
        StaticDataDao.bulkInsertTypes(
            listOf(
                type(1, "A", marketGroupId = 10),
                type(2, "B", marketGroupId = 20),
                type(3, "C", marketGroupId = null),
            ),
        )

        StaticDataDao.getTypesByMarketGroup(10).map { it.typeId } shouldBe listOf(1)
        StaticDataDao.getTypeIdsByMarketGroups(setOf(10, 20)).toSet() shouldBe setOf(1, 2)
        StaticDataDao.getAllMarketTypeIds().toSet() shouldBe setOf(1, 2)
    }

    @Test
    fun `getTypeIdsByMarketGroups on an empty set returns an empty list without querying`() {
        StaticDataDao.getTypeIdsByMarketGroups(emptySet()).shouldBeEmpty()
    }

    // ─── Stations ───────────────────────────────────────────────────────────

    private fun station(
        stationId: Long,
        name: String,
        regionId: Int = 10000002,
    ) = StaticStationModel(stationId = stationId, name = name, systemId = 30000142, regionId = regionId)

    @Test
    fun `bulkInsertStations then getStationById and getStationsByRegion round-trip`() {
        StaticDataDao.bulkInsertStations(listOf(station(60003760L, "Jita IV - Moon 4"), station(60008494L, "Amarr VIII")))

        StaticDataDao.getStationById(60003760L).shouldNotBeNull().name shouldBe "Jita IV - Moon 4"
        StaticDataDao.getStationsByRegion(10000002).size shouldBe 2
    }

    @Test
    fun `searchStations matches a substring of the name`() {
        StaticDataDao.bulkInsertStations(listOf(station(60003760L, "Jita IV - Moon 4")))

        StaticDataDao.searchStations("Jita").single().stationId shouldBe 60003760L
    }

    @Test
    fun `getCitadelCount only counts stations with an id above the NPC-station threshold`() {
        StaticDataDao.bulkInsertStations(
            listOf(
                station(60003760L, "NPC Station"), // below threshold
                station(1035466617946L, "Player Citadel"), // above threshold
            ),
        )

        StaticDataDao.getCitadelCount() shouldBe 1
    }

    // ─── Regions & systems ──────────────────────────────────────────────────

    @Test
    fun `bulkInsertRegions then getAllRegions and getRegionById round-trip`() {
        StaticDataDao.bulkInsertRegions(listOf(StaticRegionModel(10000002, "The Forge"), StaticRegionModel(10000043, "Domain")))

        StaticDataDao.getAllRegions().map { it.name } shouldBe listOf("Domain", "The Forge")
        StaticDataDao.getRegionById(10000002).shouldNotBeNull().name shouldBe "The Forge"
    }

    @Test
    fun `bulkInsertSystems then getSystemById, getSystemRegionId, and getSystemIdsByRegion round-trip`() {
        StaticDataDao.bulkInsertSystems(
            listOf(
                StaticSystemModel(30000142, "Jita", 10000002),
                StaticSystemModel(30000144, "Perimeter", 10000002),
            ),
        )

        StaticDataDao.getSystemById(30000142).shouldNotBeNull().name shouldBe "Jita"
        StaticDataDao.getSystemRegionId(30000142) shouldBe 10000002
        StaticDataDao.getSystemIdsByRegion(10000002).toSet() shouldBe setOf(30000142, 30000144)
    }

    @Test
    fun `getSystemRegionId is null for an unknown system`() {
        StaticDataDao.getSystemRegionId(999).shouldBeNull()
    }

    // ─── Jump graph ─────────────────────────────────────────────────────────

    @Test
    fun `markSystemJumpsFetched then isSystemJumpsFetched reflects it, and is false for other systems`() {
        StaticDataDao.markSystemJumpsFetched(30000142)

        StaticDataDao.isSystemJumpsFetched(30000142) shouldBe true
        StaticDataDao.isSystemJumpsFetched(30000144) shouldBe false
    }

    @Test
    fun `insertSystemJumpEdges stores the edge in both directions`() {
        StaticDataDao.insertSystemJumpEdges(listOf(30000142 to 30000144))

        val graph = StaticDataDao.getJumpGraph(listOf(30000142, 30000144))

        graph[30000142] shouldContainExactly listOf(30000144)
        graph[30000144] shouldContainExactly listOf(30000142)
    }

    @Test
    fun `getJumpGraph on an empty id set returns an empty map without querying`() {
        StaticDataDao.getJumpGraph(emptyList()).shouldBeEmptyMap()
    }

    // ─── Settings ───────────────────────────────────────────────────────────

    @Test
    fun `getSetting is null until setSetting is called, then reflects the stored value`() {
        StaticDataDao.getSetting("some.key").shouldBeNull()

        StaticDataDao.setSetting("some.key", "some-value")

        StaticDataDao.getSetting("some.key") shouldBe "some-value"
    }

    @Test
    fun `setSetting is idempotent (INSERT OR REPLACE) for the same key`() {
        StaticDataDao.setSetting("some.key", "first")
        StaticDataDao.setSetting("some.key", "second")

        StaticDataDao.getSetting("some.key") shouldBe "second"
    }

    @Test
    fun `character sales tax and broker fee default to 8 percent and 3 percent`() {
        StaticDataDao.getCharSalesTax(1) should (8.0 plusOrMinus TOLERANCE)
        StaticDataDao.getCharBrokersFee(1) should (3.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `setCharSalesTax and setCharBrokersFee override the per-character defaults`() {
        StaticDataDao.setCharSalesTax(1, 6.5)
        StaticDataDao.setCharBrokersFee(1, 2.25)

        StaticDataDao.getCharSalesTax(1) should (6.5 plusOrMinus TOLERANCE)
        StaticDataDao.getCharBrokersFee(1) should (2.25 plusOrMinus TOLERANCE)
    }

    @Test
    fun `per-character tax settings don't leak between characters`() {
        StaticDataDao.setCharSalesTax(1, 6.5)

        StaticDataDao.getCharSalesTax(2) should (8.0 plusOrMinus TOLERANCE)
    }
}

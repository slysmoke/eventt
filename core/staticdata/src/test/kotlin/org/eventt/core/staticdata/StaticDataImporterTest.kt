package org.eventt.core.staticdata

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StaticDataImporterTest {
    // ─── Types ──────────────────────────────────────────────────────────────

    @Test
    fun `parseTypeLine reads a well-formed SDE type row`() {
        // Real SDE types.jsonl rows have no packagedVolume/categoryID field at all (verified
        // against an actual export) — parseTypeLine correctly ignores anything of the sort.
        val line =
            """{"_key":34,"name":{"en":"Tritanium"},"groupID":18,"volume":0.01,
               |"portionSize":1,"published":true,"marketGroupID":1857,"iconID":100}
            """.trimMargin().replace("\n", "")

        val type = StaticDataImporter.parseTypeLine(line).shouldNotBeNull()

        type.typeId shouldBe 34
        type.name shouldBe "Tritanium"
        type.groupId shouldBe 18
        type.marketGroupId shouldBe 1857
        type.iconId shouldBe 100
        type.published shouldBe true
        type.categoryId shouldBe 0 // resolved later, in saveAll(), via the group->category join
        type.packagedVolume shouldBe 0.0 // resolved later, in saveAll(), for ships only, from ESI
    }

    @Test
    fun `parseTypeLine defaults missing optional fields`() {
        val line = """{"_key":34,"name":{"en":"Tritanium"}}"""

        val type = StaticDataImporter.parseTypeLine(line).shouldNotBeNull()

        type.groupId shouldBe 0
        type.portionSize shouldBe 1
        type.published shouldBe false
        type.marketGroupId.shouldBeNull()
        type.iconId.shouldBeNull()
    }

    @Test
    fun `parseTypeLine returns null when _key or the english name is missing`() {
        StaticDataImporter.parseTypeLine("""{"name":{"en":"No Key"}}""").shouldBeNull()
        StaticDataImporter.parseTypeLine("""{"_key":34,"name":{"de":"Nur Deutsch"}}""").shouldBeNull()
    }

    // ─── Groups ─────────────────────────────────────────────────────────────

    @Test
    fun `parseGroupLine reads a well-formed row and defaults a missing categoryID`() {
        val group = StaticDataImporter.parseGroupLine("""{"_key":18,"name":{"en":"Mineral"},"categoryID":4}""").shouldNotBeNull()
        group.groupId shouldBe 18
        group.name shouldBe "Mineral"
        group.categoryId shouldBe 4

        StaticDataImporter.parseGroupLine("""{"_key":18,"name":{"en":"Mineral"}}""").shouldNotBeNull().categoryId shouldBe 0
    }

    @Test
    fun `parseGroupLine returns null when _key or the english name is missing`() {
        StaticDataImporter.parseGroupLine("""{"name":{"en":"No Key"}}""").shouldBeNull()
        StaticDataImporter.parseGroupLine("""{"_key":18}""").shouldBeNull()
    }

    // ─── Categories ─────────────────────────────────────────────────────────

    @Test
    fun `parseCategoryLine reads a well-formed row`() {
        StaticDataImporter.parseCategoryLine("""{"_key":4,"name":{"en":"Material"}}""").shouldNotBeNull().name shouldBe "Material"
    }

    @Test
    fun `parseCategoryLine returns null when the english name is missing`() {
        StaticDataImporter.parseCategoryLine("""{"_key":4,"name":{"de":"Nur Deutsch"}}""").shouldBeNull()
    }

    // ─── Market groups ──────────────────────────────────────────────────────

    @Test
    fun `parseMarketGroupLine reads parentGroupID when present and null when absent`() {
        val withParent =
            StaticDataImporter
                .parseMarketGroupLine("""{"_key":100,"name":{"en":"Ore"},"parentGroupID":1}""")
                .shouldNotBeNull()
        withParent.parentGroupId shouldBe 1

        StaticDataImporter
            .parseMarketGroupLine("""{"_key":1,"name":{"en":"Minerals"}}""")
            .shouldNotBeNull()
            .parentGroupId
            .shouldBeNull()
    }

    @Test
    fun `parseMarketGroupLine returns null when _key or the english name is missing`() {
        StaticDataImporter.parseMarketGroupLine("""{"name":{"en":"No Key"}}""").shouldBeNull()
    }

    // ─── Regions ────────────────────────────────────────────────────────────

    @Test
    fun `parseRegionLine reads a well-formed row`() {
        StaticDataImporter.parseRegionLine("""{"_key":10000002,"name":{"en":"The Forge"}}""").shouldNotBeNull().name shouldBe "The Forge"
    }

    @Test
    fun `parseRegionLine returns null when the english name is missing`() {
        StaticDataImporter.parseRegionLine("""{"_key":10000002}""").shouldBeNull()
    }

    // ─── Systems ────────────────────────────────────────────────────────────

    @Test
    fun `parseSystemLine reads a well-formed row and defaults a missing regionID to zero`() {
        val system =
            StaticDataImporter
                .parseSystemLine("""{"_key":30000142,"name":{"en":"Jita"},"regionID":10000002}""")
                .shouldNotBeNull()
        system.regionId shouldBe 10000002

        StaticDataImporter.parseSystemLine("""{"_key":30000142,"name":{"en":"Jita"}}""").shouldNotBeNull().regionId shouldBe 0
    }

    @Test
    fun `parseSystemLine returns null when the english name is missing`() {
        StaticDataImporter.parseSystemLine("""{"_key":30000142}""").shouldBeNull()
    }

    // ─── NPC stations ───────────────────────────────────────────────────────

    @Test
    fun `parseStationLine reads a well-formed row and defaults a missing typeID to zero`() {
        val station =
            StaticDataImporter
                .parseStationLine("""{"_key":60003760,"solarSystemID":30000142,"typeID":1531}""")
                .shouldNotBeNull()
        station.stationId shouldBe 60003760L
        station.solarSystemId shouldBe 30000142
        station.typeId shouldBe 1531

        StaticDataImporter
            .parseStationLine("""{"_key":60003760,"solarSystemID":30000142}""")
            .shouldNotBeNull()
            .typeId shouldBe 0
    }

    @Test
    fun `parseStationLine returns null when _key or solarSystemID is missing`() {
        StaticDataImporter.parseStationLine("""{"solarSystemID":30000142}""").shouldBeNull()
        StaticDataImporter.parseStationLine("""{"_key":60003760}""").shouldBeNull()
    }
}

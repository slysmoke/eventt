package org.eventt.core.staticdata

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eventt.core.model.StaticStationModel
import org.junit.jupiter.api.Test

class CitadelServiceTest {
    @Test
    fun `parse reads a well-formed citadel entry keyed by its station id`() {
        val raw =
            """
            {
              "1035466617946": {
                "name": "Jita Trade Hub - A Citadel",
                "systemId": 30000142,
                "systemName": "Jita",
                "regionId": 10000002,
                "regionName": "The Forge",
                "typeId": 35832
              }
            }
            """.trimIndent()

        val stations = CitadelService.parse(raw)

        stations shouldBe
            listOf(
                StaticStationModel(
                    stationId = 1035466617946L,
                    name = "Jita Trade Hub - A Citadel",
                    systemId = 30000142,
                    systemName = "Jita",
                    regionId = 10000002,
                    regionName = "The Forge",
                    typeId = 35832,
                ),
            )
    }

    @Test
    fun `parse defaults missing optional fields rather than dropping the entry`() {
        val raw = """{"1000000000001": {"name": "Some Citadel"}}"""

        val station = CitadelService.parse(raw).single()

        station.name shouldBe "Some Citadel"
        station.systemId shouldBe 0
        station.regionId shouldBe 0
        station.systemName shouldBe ""
        station.typeId shouldBe 0
    }

    @Test
    fun `parse skips an entry with a non-numeric key`() {
        val raw = """{"not-a-station-id": {"name": "Bogus"}}"""

        CitadelService.parse(raw).shouldBeEmpty()
    }

    @Test
    fun `parse skips an entry with no name`() {
        val raw = """{"1000000000001": {"systemId": 30000142}}"""

        CitadelService.parse(raw).shouldBeEmpty()
    }

    @Test
    fun `parse handles multiple entries and an empty object`() {
        val raw =
            """
            {
              "1000000000001": {"name": "Citadel A"},
              "1000000000002": {"name": "Citadel B"}
            }
            """.trimIndent()

        CitadelService.parse(raw) shouldHaveSize 2
        CitadelService.parse("{}").shouldBeEmpty()
    }
}

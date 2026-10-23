package org.eventt.features.assets

import io.kotest.matchers.shouldBe
import org.eventt.core.database.StaticDataDao.TypeInfo
import org.eventt.core.model.AssetModel
import org.junit.jupiter.api.Test

class AssetsDataTest {
    private fun asset(
        itemId: Long,
        typeId: Int = 34,
        qty: Int = 1,
        flag: String = "Hangar",
        station: Long = JITA_44,
        price: Double = 10.0,
        singleton: Boolean = false,
        owner: Int = 1,
    ) = AssetModel(
        itemId = itemId,
        typeId = typeId,
        typeName = "T$typeId",
        quantity = qty,
        locationId = station,
        stationId = station,
        stationName = if (station == JITA_44) "Jita IV - Moon 4" else "Amarr VIII",
        regionName = "R",
        isSingleton = singleton,
        locationFlag = flag,
        estimatedPrice = price,
        characterId = owner,
    )

    @Test
    fun `placement reads ESI location flags`() {
        listOf(
            "Hangar",
            "AutoFit",
            "MedSlot3",
            "Cargo",
            "DroneBay",
            "CorpSAG2",
            "CorpDeliveries",
            "AssetSafety",
            "Implant",
        ).map(::placementOf) shouldBe
            listOf(
                Placement.HANGAR,
                Placement.CONTAINER,
                Placement.FITTED,
                Placement.SHIP_HOLD,
                Placement.SHIP_HOLD,
                Placement.CORP_HANGAR,
                Placement.DELIVERIES,
                Placement.ASSET_SAFETY,
                Placement.OTHER,
            )
    }

    @Test
    fun `stacks of one type in one place merge, volumes use packaged size unless assembled`() {
        val info = mapOf(34 to TypeInfo("Mineral", "Material", 0.01, 0.01), 600 to TypeInfo("Frigate", "Ship", 2500.0, 16000.0))
        val l =
            lines(
                listOf(
                    asset(1, qty = 100),
                    asset(2, qty = 50),
                    asset(3, qty = 10, station = 60008494L),
                    asset(4, typeId = 600, price = 1e6, singleton = true, station = 60008494L),
                    asset(5, typeId = 600, price = 1e6, station = 60008494L),
                ),
                info,
                ownerOf = { null },
                unknownPlace = "?",
            )

        l.size shouldBe 3
        l.single { it.typeId == 34 && it.place.isJita }.qty shouldBe 150
        // One assembled hull (16 000 m³) and one packaged (2 500 m³) in the same place: one line, 18 500 m³.
        l.single { it.typeId == 600 }.m3 shouldBe 18_500.0

        val t = totals(l)
        t.jitaValue shouldBe 1500.0
        t.outsideValue shouldBe 100.0 + 2e6
        t.places shouldBe 2
    }
}

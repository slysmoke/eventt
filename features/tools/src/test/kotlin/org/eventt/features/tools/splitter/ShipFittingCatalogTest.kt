package org.eventt.features.tools.splitter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShipFittingCatalogTest {
    @Test
    fun `Iteron Mark V gets the 8 hardcoded cargo-expander extras`() {
        val extras = ShipFittingCatalog.extraFittingItemsFor(657)
        extras.size shouldBe 8
        extras.count { it.typeId == 1319 && it.flag.startsWith("LoSlot") } shouldBe 5
        extras.count { it.typeId == 31125 && it.flag.startsWith("RigSlot") } shouldBe 2
        extras.count { it.typeId == 31119 && it.flag == "RigSlot2" } shouldBe 1
    }

    @Test
    fun `other ship types get no extras`() {
        ShipFittingCatalog.extraFittingItemsFor(20185).isEmpty() shouldBe true
        ShipFittingCatalog.HAULERS.size shouldBe 11
    }
}

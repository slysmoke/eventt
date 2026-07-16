package org.eventt.core.nostr

import io.kotest.matchers.shouldBe
import org.eventt.core.database.NostrOrderModel
import org.junit.jupiter.api.Test

class ReservationServiceTest {
    private fun order(
        qtyRemaining: Long = 1000,
        minLot: Long = 100,
        minLotUnit: String = "units",
        price: Double = 5.0,
    ) = NostrOrderModel(
        orderUuid = "uuid",
        pubkey = "pk",
        eventId = "id",
        createdAt = 0,
        side = "sell",
        typeId = 34,
        regionId = 10000002,
        price = price,
        qtyTotal = 1000,
        qtyRemaining = qtyRemaining,
        minLot = minLot,
        minLotUnit = minLotUnit,
        traderChar = "",
        traderCharId = null,
        expiration = 0,
        rawEventJson = "",
        isMine = true,
    )

    @Test
    fun `accepts a qty within remaining and at or above min lot`() {
        ReservationService.isValidRequestQty(100, order()) shouldBe true
        ReservationService.isValidRequestQty(1000, order()) shouldBe true
    }

    @Test
    fun `rejects non-positive and above-remaining quantities`() {
        ReservationService.isValidRequestQty(0, order()) shouldBe false
        ReservationService.isValidRequestQty(-5, order()) shouldBe false
        ReservationService.isValidRequestQty(1001, order()) shouldBe false
    }

    @Test
    fun `rejects qty below min lot in units`() {
        ReservationService.isValidRequestQty(99, order(minLot = 100)) shouldBe false
    }

    @Test
    fun `min lot in ISK compares against qty times price`() {
        // 100 units * 5.0 ISK = 500 ISK ≥ min_lot 500 ISK
        ReservationService.isValidRequestQty(100, order(minLot = 500, minLotUnit = "isk")) shouldBe true
        // 99 * 5.0 = 495 < 500
        ReservationService.isValidRequestQty(99, order(minLot = 500, minLotUnit = "isk")) shouldBe false
    }
}

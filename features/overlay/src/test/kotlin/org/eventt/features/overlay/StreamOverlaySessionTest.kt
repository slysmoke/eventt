package org.eventt.features.overlay

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.database.ActiveOrderDao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ORDER_ID = 1001L

class StreamOverlaySessionTest {
    @BeforeEach
    fun setUp() {
        mockkObject(ActiveOrderDao)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ActiveOrderDao)
    }

    private fun sellOrder(
        orderId: Long,
        volumeTotal: Int,
        volumeRemaining: Int,
    ) = ActiveOrderDao.ActiveOrderRecord(
        orderId = orderId,
        typeId = 34,
        typeName = "Tritanium",
        locationId = 1,
        regionId = 1,
        stationName = "Jita",
        price = 5.0,
        volumeTotal = volumeTotal,
        volumeRemaining = volumeRemaining,
        isBuyOrder = false,
        duration = 90,
        issued = "2024-01-01T00:00:00",
        state = "active",
        issuedByCharId = null,
        characterId = 1,
        corporationId = null,
    )

    @Test
    fun `no fill since baseline reports zero`() {
        every { ActiveOrderDao.getAll() } returns listOf(sellOrder(ORDER_ID, volumeTotal = 100, volumeRemaining = 60))
        StreamOverlaySession.reset()

        StreamOverlaySession.provisionalFilledQty(ORDER_ID, currentFilled = 40) shouldBe 0
    }

    @Test
    fun `volume_remaining dropping since baseline reports the units sold`() {
        every { ActiveOrderDao.getAll() } returns listOf(sellOrder(ORDER_ID, volumeTotal = 100, volumeRemaining = 60))
        StreamOverlaySession.reset()

        // order refilled to 60->35 remaining since the baseline snapshot: 25 more units sold
        StreamOverlaySession.provisionalFilledQty(ORDER_ID, currentFilled = 65) shouldBe 25
    }

    @Test
    fun `an order placed after session start has no baseline entry, so its whole fill counts`() {
        every { ActiveOrderDao.getAll() } returns emptyList()
        StreamOverlaySession.reset()

        StreamOverlaySession.provisionalFilledQty(orderId = 9999L, currentFilled = 12) shouldBe 12
    }

    @Test
    fun `filled going backwards relative to baseline never reports negative`() {
        every { ActiveOrderDao.getAll() } returns listOf(sellOrder(ORDER_ID, volumeTotal = 100, volumeRemaining = 40))
        StreamOverlaySession.reset()

        StreamOverlaySession.provisionalFilledQty(ORDER_ID, currentFilled = 50) shouldBe 0
    }
}

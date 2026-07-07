package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.esi.EsiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class StationTradingQueueTest {
    @BeforeEach
    fun setUp() {
        mockkObject(EsiClient)
        every { EsiClient.openMarketWindow(any(), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        StationTradingQueue.clear()
        StationTradingQueue.copyVolume = true
        unmockkObject(EsiClient)
    }

    @Test
    fun `PendingStationItem priceToSet always steps one tick above the best competing buy`() {
        val item = PendingStationItem(charId = 1, typeId = 34, typeName = "Tritanium", bestBuy = 100.0, volume = 500)

        // step(100.0) = 0.1 -> lead the current top buyer by one tick
        item.priceToSet should (100.1 plusOrMinus TOLERANCE)
    }

    private fun item(
        typeId: Int,
        typeName: String,
    ) = PendingStationItem(charId = 1, typeId = typeId, typeName = typeName, bestBuy = 10.0, volume = 100)

    @Test
    fun `with copyVolume on, each item takes two presses (price then volume) before advancing`() {
        StationTradingQueue.copyVolume = true
        StationTradingQueue.update(listOf(item(1, "A"), item(2, "B")))

        StationTradingQueue.processNext() // item 1, PRICE
        StationTradingQueue.currentTypeId.value shouldBe 1
        StationTradingQueue.currentPosition shouldBe 1

        StationTradingQueue.processNext() // item 1, VOLUME -> advances
        StationTradingQueue.currentTypeId.value shouldBe 1
        StationTradingQueue.currentPosition shouldBe 2

        StationTradingQueue.processNext() // item 2, PRICE
        StationTradingQueue.currentTypeId.value shouldBe 2
        StationTradingQueue.currentPosition shouldBe 2

        StationTradingQueue.processNext() // item 2, VOLUME -> advances, wraps
        StationTradingQueue.currentTypeId.value shouldBe 2
        StationTradingQueue.currentPosition shouldBe 1

        StationTradingQueue.processNext() // wrapped back to item 1
        StationTradingQueue.currentTypeId.value shouldBe 1
    }

    @Test
    fun `with copyVolume off, every press advances directly to the next item`() {
        StationTradingQueue.copyVolume = false
        StationTradingQueue.update(listOf(item(1, "A"), item(2, "B")))

        StationTradingQueue.processNext()
        StationTradingQueue.currentTypeId.value shouldBe 1

        StationTradingQueue.processNext()
        StationTradingQueue.currentTypeId.value shouldBe 2

        StationTradingQueue.processNext() // wraps
        StationTradingQueue.currentTypeId.value shouldBe 1
    }

    @Test
    fun `processNext on an empty queue does nothing`() {
        StationTradingQueue.processNext()

        StationTradingQueue.currentTypeId.value shouldBe null
    }

    @Test
    fun `clear resets the queue, position, and current item`() {
        StationTradingQueue.update(listOf(item(1, "A")))
        StationTradingQueue.processNext()

        StationTradingQueue.clear()

        StationTradingQueue.size shouldBe 0
        StationTradingQueue.currentPosition shouldBe 0
        StationTradingQueue.currentTypeId.value shouldBe null
    }
}
